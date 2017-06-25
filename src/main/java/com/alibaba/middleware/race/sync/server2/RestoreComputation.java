package com.alibaba.middleware.race.sync.server2;

import com.alibaba.middleware.race.sync.server2.operations.*;

import java.util.HashSet;
import java.util.concurrent.ExecutorService;

import static com.alibaba.middleware.race.sync.server2.PipelinedComputation.EVAL_WORKER_NUM;
import static com.alibaba.middleware.race.sync.server2.PipelinedComputation.finalResultMap;

/**
 * Created by yche on 6/18/17.
 */
public class RestoreComputation {
    public static YcheHashMap recordMap = new YcheHashMap(24 * 1024 * 1024);

    public static HashSet<LogOperation> inRangeRecordSet = new HashSet<>();

    static void compute(LogOperation[] logOperations) {
        for (int i = 0; i < logOperations.length; i++) {
            LogOperation logOperation = logOperations[i];
            if (logOperation instanceof UpdateOperation) {
                // update
                InsertOperation insertOperation = (InsertOperation) recordMap.get(logOperation); //2
                insertOperation.mergeAnother((UpdateOperation) logOperation); //3
            } else if (logOperation instanceof UpdateKeyOperation) {
                InsertOperation insertOperation = (InsertOperation) recordMap.get(logOperation); //2
                if (PipelinedComputation.isKeyInRange(logOperation.relevantKey)) {
                    inRangeRecordSet.remove(logOperation);
                }

                insertOperation.changePK(((UpdateKeyOperation) logOperation).changedKey); //4
                recordMap.put(insertOperation); //5

                if (PipelinedComputation.isKeyInRange(insertOperation.relevantKey)) {
                    inRangeRecordSet.add(insertOperation);
                }
            } else if (logOperation instanceof DeleteOperation) {
//                recordMap.remove(logOperation);
                if (PipelinedComputation.isKeyInRange(logOperation.relevantKey)) {
                    inRangeRecordSet.remove(logOperation);
                }
            } else {
                // insert
                recordMap.put(logOperation); //1
                if (PipelinedComputation.isKeyInRange(logOperation.relevantKey)) {
                    inRangeRecordSet.add(logOperation);
                }
            }
        }
    }

    private static class EvalTask implements Runnable {
        int start;
        int end;
        LogOperation[] logOperations;

        public EvalTask(int start, int end, LogOperation[] logOperations) {
            this.start = start;
            this.end = end;
            this.logOperations = logOperations;
        }

        @Override
        public void run() {
            for (int i = start; i < end; i++) {
                InsertOperation insertOperation = (InsertOperation) logOperations[i];
                finalResultMap.put(insertOperation.relevantKey, insertOperation.getOneLineBytesEfficient());
            }
        }
    }

    // used by master thread
    static void parallelEvalAndSend(ExecutorService evalThreadPool) {
        LogOperation[] insertOperations = inRangeRecordSet.toArray(new LogOperation[0]);
        int avgTask = insertOperations.length / EVAL_WORKER_NUM;
        for (int i = 0; i < insertOperations.length; i += avgTask) {
            evalThreadPool.execute(new EvalTask(i, Math.min(i + avgTask, insertOperations.length), insertOperations));
        }
    }
}
