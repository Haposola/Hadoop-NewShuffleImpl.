package org.apache.hadoop.mapreduce.newShuffleImpl;

/**
 * Created by haposola on 16-6-8.
 */

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.mapred.MapTaskCompletionEventsUpdate;
import org.apache.hadoop.mapred.TaskCompletionEvent;
import org.apache.hadoop.mapred.TaskUmbilicalProtocol;
import org.apache.hadoop.mapreduce.TaskAttemptID;
import org.apache.hadoop.mapred.JobID;
import java.io.IOException;

public class NewEventFetcher<K,V> extends Thread {
    private static final long SLEEP_TIME = 1000;
    private static final int MAX_RETRIES = 10;
    private static final int RETRY_PERIOD = 5000;
    private static final Log LOG = LogFactory.getLog(NewEventFetcher.class);

    private final TaskUmbilicalProtocol umbilical;
    private JobID jobId;
    private int fromEventIdx = 0;
    private final int maxEventsToFetch;

    int totalMaps;
    private volatile boolean stopped = false;

    public NewEventFetcher(JobID jobId,
                        TaskUmbilicalProtocol umbilical,
                        int maxEventsToFetch,int totalMaps
    ) {
        setName("EventFetcher for fetching Map Completion Events");
        setDaemon(true);
        this.umbilical = umbilical;
        this.maxEventsToFetch = maxEventsToFetch;
        this.totalMaps=totalMaps;
    }

    @Override
    public void run() {
        int failures = 0;
        LOG.info( "Thread started: " + getName());
        int mapsCount=0;
        try {
            while (!stopped && !Thread.currentThread().isInterrupted()) {
                try {
                    int numNewMaps = getMapCompletionEvents();
                    failures = 0;

                    if (numNewMaps > 0) {
                        LOG.info( ": " + "Got " + numNewMaps + " new map-outputs");
                    }
                    LOG.debug("GetMapEventsThread about to sleep for " + SLEEP_TIME);
                    mapsCount+=numNewMaps;
                    if(mapsCount==totalMaps){
                        LOG.info("Reducer knows map all finished");
                        return;
                    }
                    if (!Thread.currentThread().isInterrupted()) {
                        Thread.sleep(SLEEP_TIME);
                    }
                } catch (InterruptedException e) {
                    LOG.info("EventFetcher is interrupted.. Returning");
                    this.interrupt();
                    return;
                } catch (IOException ie) {
                    LOG.info("Exception in getting events", ie);
                    // check to see whether to abort
                    if (++failures >= MAX_RETRIES) {
                        throw new IOException("too many failures downloading events", ie);
                    }
                    // sleep for a bit
                    if (!Thread.currentThread().isInterrupted()) {
                        Thread.sleep(RETRY_PERIOD);
                    }
                }
            }
        } catch (InterruptedException e) {
            return;
        } catch (Throwable t) {
            t.printStackTrace();
            return;
        }
    }

    public void shutDown() {
        this.stopped = true;
        interrupt();
        try {
            join(5000);
        } catch(InterruptedException ie) {
            LOG.warn("Got interrupted while joining " + getName(), ie);
        }
    }

    /**
     * Queries the TaskTracker for a set of map-completion events
     * from a given event ID.
     * @throws IOException
     */
    protected int getMapCompletionEvents()
            throws IOException, InterruptedException {

        int numNewMaps = 0;
        TaskCompletionEvent events[] = null;

        do {
            MapTaskCompletionEventsUpdate update =
                    umbilical.getMapCompletionEvents(
                            jobId,
                            fromEventIdx,
                            maxEventsToFetch
                            );
            events = update.getMapTaskCompletionEvents();
            LOG.debug("Got " + events.length + " map completion events from " +
                    fromEventIdx);

            assert !update.shouldReset() : "Unexpected legacy state";

            // Update the last seen event ID
            fromEventIdx += events.length;

            // Process the TaskCompletionEvents:
            // 1. Save the SUCCEEDED maps in knownOutputs to fetch the outputs.
            // 2. Save the OBSOLETE/FAILED/KILLED maps in obsoleteOutputs to stop
            //    fetching from those maps.
            // 3. Remove TIPFAILED maps from neededOutputs since we don't need their
            //    outputs at all.
            for (TaskCompletionEvent event : events) {

                if (TaskCompletionEvent.Status.SUCCEEDED == event.getTaskStatus()) {
                    ++numNewMaps;
                }
            }
        } while (events.length == maxEventsToFetch);

        return numNewMaps;
    }
}
