package cz.luigismp.screen;

interface SourceWorker {

    boolean start();

    boolean stop();

    void requestStop();

    String state();

    String lastError();

    boolean isRunning();

    boolean isTerminated();

    int sourceWidth();

    int sourceHeight();

    long reconnects();

    long lastFrameAgeMillis();
}
