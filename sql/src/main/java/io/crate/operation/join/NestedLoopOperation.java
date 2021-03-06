/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.operation.join;

import com.google.common.base.Predicate;
import com.google.common.base.Throwables;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import io.crate.concurrent.CompletionListenable;
import io.crate.core.collections.Row;
import io.crate.core.collections.RowN;
import io.crate.core.collections.RowNull;
import io.crate.operation.projectors.*;
import io.crate.planner.node.dql.join.JoinType;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;


/**
 * Push based nested-loop-like implementation:
 * <p>
 * <h2>cross join or inner join</h2>
 * <pre>
 *     for (leftRow in left) {
 *         for (rightRow in right) {
 *             if matched
 *                  emit(left + right)
 *         }
 *     }
 * </pre>
 * <p>
 * But push based:
 * <pre>
 *    +------+          +----+
 *    |  lU  |          | rU |
 *    +------+          +----+
 *          \             /
 *          \            /
 *         left        right
 *            NL-Operation
 *                 |
 *                 |
 *             RowReceiver
 * </pre>
 * <p>
 * <p>
 * <h2>left join</h2>:
 * <pre>
 *     for (leftRow in left) {
 *         for (rightRow in right) {
 *             matched?
 *                  emit
 *         }
 *         if (noRightRowMatched) {
 *             emitWithRightAsNull
 *         }
 *     }
 *
 * </pre>
 * <p>
 * <p>
 * <h2>right join</h2>
 * <p>
 * Adds an additional loop over rightRows after the nested-loop to emit any rows that didn't have any matches:
 * <pre>
 *      for (leftRow in left) {
 *          for (rightRow in right) {
 *              if matched {
 *                  markMatched(position)
 *                  emitIfMatch
 *              }
 *          }
 *      }
 *
 *      for (rightRow in right) {
 *          if notMatched(position) {
 *              emitWithLeftAsNull
 *          }
 *      }
 * </pre>
 * As a consequence of this algorithm the ordering of the emitted rows
 * doesn't match the order of the rows as received from the upstreams.
 * <p>
 * <p>
 * <p>
 * <h2>Full join</h2>
 * <p>
 * is a combination of left-join and right-join.
 * <p>
 * <p>
 * <h2>Implementation details:</h2>
 * <p>
 * Both upstreams start concurrently. {@link #leadAcquired} is used to pause the first upstream and at then point
 * it is single threaded.
 * <p>
 * There are a couple of edge cases (like if one side is empty), but the common case looks as follows:
 * <p>
 * <pre>
 *     lU:                                          rU:
 *                                                  r.setNextRow(row)
 *     r.setNextRow(row)                                leadAcquired
 *         not leadAcquired                             return pause
 *         lastRow = row                            THREAD EXIT
 *         switchToRight()
 *              resumeHandle.resume (this might actually call repeat)
 *                  rU:
 *                  r.setNextRow
 *                  [...]
 *                  r.setNextRow
 *                  r.finish
 *         return CONTINUE
 *     r.setNextRow(row)
 *         lastRow = row
 *         switchToRight()
 *              [... same as before ...]
 * </pre>
 */
public class NestedLoopOperation implements CompletionListenable {

    private final static ESLogger LOGGER = Loggers.getLogger(NestedLoopOperation.class);
    private final boolean traceEnabled = LOGGER.isTraceEnabled();

    private final SettableFuture<Void> completionFuture = SettableFuture.create();

    private final LeftRowReceiver left;
    private final RightRowReceiver right;

    private final int phaseId;
    private final RowReceiver downstream;
    private final Predicate<Row> joinPredicate;
    private final JoinType joinType;

    private volatile Throwable upstreamFailure;
    private volatile boolean stop = false;
    private volatile boolean emitRightJoin = false;


    @Override
    public ListenableFuture<?> completionFuture() {
        return completionFuture;
    }

    private final AtomicBoolean leadAcquired = new AtomicBoolean(false);

    public NestedLoopOperation(int phaseId,
                               RowReceiver rowReceiver,
                               Predicate<Row> joinPredicate,
                               JoinType joinType,
                               int leftNumOutputs,
                               int rightNumOutputs) {
        this.phaseId = phaseId;
        this.downstream = rowReceiver;
        this.joinPredicate = joinPredicate;
        this.joinType = joinType;
        left = new LeftRowReceiver();
        if (JoinType.RIGHT == joinType || JoinType.FULL == joinType) {
            right = new RightJoinRightRowReceiver(leftNumOutputs, rightNumOutputs);
        } else {
            right = new RightRowReceiver(leftNumOutputs, rightNumOutputs);
        }
    }

    public ListenableRowReceiver leftRowReceiver() {
        return left;
    }

    public ListenableRowReceiver rightRowReceiver() {
        return right;
    }

    private static class CombinedRow implements Row {

        volatile Row outerRow;
        volatile Row innerRow;
        final Row outerNullRow;
        final Row innerNullRow;

        CombinedRow(int outerOutputSize, int innerOutputSize) {
            outerNullRow = new RowNull(outerOutputSize);
            innerNullRow = new RowNull(innerOutputSize);
        }

        @Override
        public int size() {
            return outerRow.size() + innerRow.size();
        }

        @Override
        public Object get(int index) {
            if (index < outerRow.size()) {
                return outerRow.get(index);
            }
            return innerRow.get(index - outerRow.size());
        }

        @Override
        public Object[] materialize() {
            Object[] left = outerRow.materialize();
            Object[] right = innerRow.materialize();

            Object[] newRow = new Object[left.length + right.length];
            System.arraycopy(left, 0, newRow, 0, left.length);
            System.arraycopy(right, 0, newRow, left.length, right.length);
            return newRow;
        }

        @Override
        public String toString() {
            return "CombinedRow{" +
                   " outer=" + outerRow +
                   ", inner=" + innerRow +
                   '}';
        }
    }

    private abstract class AbstractRowReceiver implements ListenableRowReceiver {

        final SettableFuture<Void> finished = SettableFuture.create();
        final AtomicReference<ResumeHandle> resumeable = new AtomicReference<>(ResumeHandle.INVALID);
        boolean upstreamFinished = false;
        boolean firstCall = true;

        private boolean finishDownstreamIfBothSidesFinished() {
            if (traceEnabled) {
                LOGGER.trace("phase={} method=tryFinish side={} leftFinished={} rightFinished={}",
                    phaseId, getClass().getSimpleName(), left.upstreamFinished, right.upstreamFinished);
            }
            if (!left.upstreamFinished || !right.upstreamFinished) {
                return false;
            }
            if ((JoinType.RIGHT == joinType || JoinType.FULL == joinType) && !emitRightJoin) {
                emitRightJoin = true;
                return false;
            }
            if (upstreamFailure == null) {
                downstream.finish(RepeatHandle.UNSUPPORTED);
                completionFuture.set(null);
                left.finished.set(null);
                right.finished.set(null);
            } else {
                downstream.fail(upstreamFailure);
                completionFuture.setException(upstreamFailure);
                left.finished.setException(upstreamFailure);
                right.finished.setException(upstreamFailure);
            }
            return true;
        }

        private boolean exitIfFirstCallAndLead() {
            upstreamFinished = true;
            if (firstCall) {
                firstCall = false;
                if (leadAcquired.compareAndSet(false, true)) {
                    if (traceEnabled) {
                        LOGGER.trace("phase={} side={} method=exitIfFirstCallAndLead leadAcquired",
                            phaseId, getClass().getSimpleName());
                    }
                    return true;
                }
            }
            return false;
        }

        boolean tryFinish() {
            return exitIfFirstCallAndLead() || finishDownstreamIfBothSidesFinished();
        }

        @Override
        public ListenableFuture<Void> finishFuture() {
            return finished;
        }

        @Override
        public void kill(Throwable throwable) {
            killBoth(throwable);
            downstream.kill(throwable);
            completionFuture.setException(throwable);
        }
    }

    private void killBoth(Throwable throwable) {
        // make sure that switchTo unblocks
        left.resumeable.set(ResumeHandle.NOOP);
        right.resumeable.set(ResumeHandle.NOOP);

        stop = true;
        left.finished.setException(throwable);
        right.finished.setException(throwable);
    }

    private class LeftRowReceiver extends AbstractRowReceiver {

        private volatile Row lastRow = null; // TODO: volatile is only required for first access
        private boolean isPaused = true;

        @Override
        public Result setNextRow(Row row) {
            if (stop) {
                LOGGER.trace("phase={} side=left method=setNextRow stop=true", phaseId);
                return Result.STOP;
            }
            // no need to materialize as it will be used before upstream is resumed
            // TODO: is this really safe?
            lastRow = row;
            if (firstCall) {
                firstCall = false;
                if (leadAcquired.compareAndSet(false, true)) {
                    LOGGER.trace("phase={} side=left method=setNextRow action=leadAcquired->pause", phaseId);
                    return Result.PAUSE;
                }
            }
            LOGGER.trace("phase={} side=left method=setNextRow switchOnPause=true", phaseId);
            isPaused = false;
            switchTo(right.resumeable);
            if (right.upstreamFinished) {
                return Result.CONTINUE;
            }
            return Result.PAUSE;
        }

        @Override
        public void pauseProcessed(ResumeHandle resumeable) {
            isPaused = true;
            if (!this.resumeable.compareAndSet(ResumeHandle.INVALID, resumeable)) {
                throw new AssertionError("resumeable was already set");
            }
            LOGGER.trace("phase={} side=left method=pauseProcessed", phaseId);
        }

        @Override
        public void finish(RepeatHandle repeatHandle) {
            LOGGER.trace("phase={} side=left method=finish", phaseId);
            doFinish();
        }

        @Override
        public void fail(Throwable throwable) {
            LOGGER.trace("phase={} side=left method=fail error={}", phaseId, throwable);
            upstreamFailure = throwable;
            doFinish();
        }

        private void doFinish() {
            if (!tryFinish()) {
                switchTo(right.resumeable);
            }
        }

        @Override
        public Set<Requirement> requirements() {
            return downstream.requirements();
        }
    }

    private void switchTo(AtomicReference<ResumeHandle> atomicResumeable) {
        ResumeHandle resumeHandle = busyGet(atomicResumeable);
        LOGGER.trace("phase={} method=switchTo resumeable={}", phaseId, resumeHandle);
        resumeHandle.resume(false);
    }

    private ResumeHandle busyGet(AtomicReference<ResumeHandle> atomicResumeable) {
        ResumeHandle resumeHandle;
        int sleep = 10;
        /*
         * Usually this loop exits immediately.
         * There is only a race condition during the "start/lead-acquisition" where that's not the case:
         * E.g.
         *
         * <pre>
         * side=right method=setNextRow action=leadAcquired->pause
         * side=left method=setNextRow switchOnPause=true
         * side=left method=pauseProcessed switchOnPause=true
         * side=right method=pauseProcessed
         * </pre>
         */
        while ((resumeHandle = atomicResumeable.get()) == ResumeHandle.INVALID) {
            try {
                Thread.sleep(sleep *= 2);
                if (sleep > 100) {
                    LOGGER.warn("phase={} method=switchTo sleep={} SLOW!", phaseId, sleep);
                }
            } catch (InterruptedException e) {
                LOGGER.error("phase={} method=switchTo timeout", phaseId);
                Thread.currentThread().interrupt();
                throw Throwables.propagate(e);
            }
        }
        atomicResumeable.set(ResumeHandle.INVALID);
        return resumeHandle;
    }

    private class RightRowReceiver extends AbstractRowReceiver {

        final CombinedRow combinedRow;
        private final Set<Requirement> requirements;

        Row lastRow = null;
        private boolean suspendThread = false;
        boolean matchedJoinPredicate = false;

        RightRowReceiver(int leftNumOutputs, int rightNumOutputs) {
            requirements = Requirements.add(downstream.requirements(), Requirement.REPEAT);
            combinedRow = new CombinedRow(leftNumOutputs, rightNumOutputs);
        }

        @Override
        public void pauseProcessed(final ResumeHandle resumeHandle) {
            LOGGER.trace("phase={} side=right method=pauseProcessed suspendThread={}", phaseId, suspendThread);

            if (suspendThread) {
                suspendThread = false;
                // right side started before left side did and so the thread is suspended.
                if (!this.resumeable.compareAndSet(ResumeHandle.INVALID, new RightResumeHandle(resumeHandle))) {
                    throw new IllegalStateException("Right resumable wasn't null. It should be set to null after use");
                }
            } else {
                // pause request came from the downstream, so let it handle the resume.
                downstream.pauseProcessed(resumeHandle);
            }
        }

        @Override
        public Result setNextRow(final Row rightRow) {
            if (stop) {
                return Result.STOP;
            }
            if (firstCall) {
                firstCall = false;
                if (leadAcquired.compareAndSet(false, true)) {
                    lastRow = new RowN(rightRow.materialize());
                    LOGGER.trace("phase={} side=right method=setNextRow action=leadAcquired->pause", phaseId);
                    suspendThread = true;
                    return Result.PAUSE;
                } else if (left.lastRow == null) {
                    assert left.upstreamFinished : "If left acquired lead it should either set a lastRow or be finished";
                    return Result.STOP;
                }
            }
            return emitRow(rightRow);
        }

        protected Result emitRow(Row row) {
            combinedRow.outerRow = left.lastRow;
            combinedRow.innerRow = row;

            // Check join condition
            if (!joinPredicate.apply(combinedRow)) {
                return Result.CONTINUE;
            }
            matchedJoinPredicate = true;
            return emitRowAndTrace(combinedRow);
        }

        RowReceiver.Result emitRowAndTrace(Row row) {
            RowReceiver.Result result = downstream.setNextRow(row);
            if (traceEnabled && result != Result.CONTINUE) {
                LOGGER.trace("phase={} side=right method=emitRow result={}", phaseId, result);
            }
            if (result == Result.STOP) {
                stop = true;
            }
            return result;
        }

        @Override
        public void finish(final RepeatHandle repeatHandle) {
            LOGGER.trace("phase={} side=right method=finish firstCall={}", phaseId, firstCall);

            this.resumeable.set(new ResumeHandle() {
                @Override
                public void resume(boolean async) {
                    RightRowReceiver.this.matchedJoinPredicate = false;
                    RightRowReceiver.this.upstreamFinished = false;
                    repeatHandle.repeat();
                }
            });

            if ((JoinType.LEFT == joinType || JoinType.FULL == joinType) &&
                !matchedJoinPredicate && left.lastRow != null && !emitRightJoin) {
                // emit row with right one nulled
                combinedRow.outerRow = left.lastRow;
                combinedRow.innerRow = combinedRow.innerNullRow;
                Result result = emitRowAndTrace(combinedRow);
                LOGGER.trace("phase={} side=right method=finish firstCall={} emitNullRow result={}", phaseId, firstCall, result);
                switch (result) {
                    case CONTINUE:
                        break;
                    case PAUSE:
                        downstream.pauseProcessed(new ResumeHandle() {
                            @Override
                            public void resume(boolean async) {
                                if (tryFinish()) {
                                    return;
                                }
                                switchTo(left.resumeable);
                            }
                        });
                        // return without setting upstreamFinished=true to ensure left-side get's paused (if it isn't already)
                        // this way the resumeHandle call can un-pause it
                        return;
                    case STOP:
                        stop = true;
                        break;
                }
            }
            if (tryFinish()) {
                return;
            }
            if (left.isPaused) {
                switchTo(left.resumeable);
            }
            // else: right-side iteration is within the left#setNextRow stack, returning here will
            // continue on the left side
        }

        @Override
        public void fail(Throwable throwable) {
            LOGGER.trace("phase={} side=right method=fail error={}", phaseId, throwable);
            upstreamFailure = throwable;
            if (tryFinish()) {
                return;
            }
            if (left.isPaused) {
                switchTo(left.resumeable);
            }
        }

        @Override
        public Set<Requirement> requirements() {
            return requirements;
        }

        /**
         * This Handle is only used if the right side starts before the left and is paused.
         * <p>
         * After that the right side can only be paused by a downstream in which case the upstreams handle is passed through.
         */
        private class RightResumeHandle implements ResumeHandle {
            private final ResumeHandle delegate;

            RightResumeHandle(ResumeHandle delegate) {
                this.delegate = delegate;
            }

            @Override
            public void resume(boolean async) {
                if (left.upstreamFinished) {
                    stop = true;
                    delegate.resume(async);
                    return;
                }

                assert lastRow != null : "lastRow should be present";
                Result result = emitRow(lastRow);
                lastRow = null;
                switch (result) {
                    case CONTINUE:
                        break;
                    case PAUSE:
                        downstream.pauseProcessed(delegate);
                        return;
                    case STOP:
                        stop = true;
                        break; // need to resume so that STOP can be processed
                }
                delegate.resume(async);
            }
        }
    }

    private class RightJoinRightRowReceiver extends RightRowReceiver {

        private final LuceneLongBitSetWrapper matchedJoinRows = new LuceneLongBitSetWrapper();
        private long rowPosition = -1;

        RightJoinRightRowReceiver(int leftNumOutputs, int rightNumOutputs) {
            super(leftNumOutputs, rightNumOutputs);
        }

        @Override
        public Result setNextRow(final Row rightRow) {
            if (!stop) {
                rowPosition++;
            }
            return super.setNextRow(rightRow);
        }

        protected Result emitRow(Row row) {
            if (emitRightJoin) {
                if (!matchedJoinRows.get(rowPosition)) {
                    combinedRow.outerRow = combinedRow.outerNullRow;
                    combinedRow.innerRow = row;
                    return emitRowAndTrace(combinedRow);
                }
                return Result.CONTINUE;
            } else {
                combinedRow.outerRow = left.lastRow;
                combinedRow.innerRow = row;

                // Check join condition
                if (!joinPredicate.apply(combinedRow)) {
                    return Result.CONTINUE;
                }
                matchedJoinPredicate = true;
                matchedJoinRows.set(rowPosition);
                return emitRowAndTrace(combinedRow);
            }
        }

        @Override
        public void finish(final RepeatHandle repeatHandle) {
            rowPosition = -1;
            super.finish(repeatHandle);
        }
    }
}
