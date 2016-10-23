package org.apache.hadoop.mapred;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Created by haposola on 16-6-2.
 * Another class moved from my project into sourcecode
 * Mostly copied form YarnChild.java
 * The BIGGEST different is how we get a Task instance
 * In YarnChild it is through a RPC call umbilical.getTask
 * WE SHOULD build a task ourselves.
 */
public class ShuffleReceiverDedicated {
    private static final Log LOG = LogFactory.getLog(ShuffleReceiverDedicated.class);





    public static void main(String[]args)throws  Throwable {
        LOG.info("I am still there, shuffle Receiver");

    }


}
