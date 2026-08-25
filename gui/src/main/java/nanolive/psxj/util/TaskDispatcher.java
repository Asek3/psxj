package nanolive.psxj.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class TaskDispatcher implements AutoCloseable {

    private final ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public void execute(Runnable runnable) {
        virtualExecutor.submit(runnable);
    }

    @Override
    public void close() {
        virtualExecutor.close();
    }
}
