package com.washingtonpost.kinesis.sample;

import com.amazonaws.services.kinesis.model.Record;
import com.amazonaws.services.kinesis.clientlibrary.exceptions.InvalidStateException;
import com.amazonaws.services.kinesis.clientlibrary.exceptions.ShutdownException;
import com.amazonaws.services.kinesis.clientlibrary.exceptions.ThrottlingException;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessorCheckpointer;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.v2.IRecordProcessor;
import com.amazonaws.services.kinesis.clientlibrary.types.InitializationInput;
import com.amazonaws.services.kinesis.clientlibrary.types.ProcessRecordsInput;
import com.amazonaws.services.kinesis.clientlibrary.types.ShutdownInput;
import com.amazonaws.services.kinesis.clientlibrary.types.ShutdownReason;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * Created by findleyr on 10/4/16.
 */
public class RecordProcessor implements IRecordProcessor {

    private static final Log LOG = LogFactory.getLog(RecordProcessor.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private InitializationInput initializationInput;

    // Backoff and retry settings
    private static final long BACKOFF_TIME_IN_MILLIS = 3000L;
    private static final int NUM_RETRIES = 10;

    // Checkpoint about once a minute
    private static final long CHECKPOINT_INTERVAL_MILLIS = 60000L;
    private long nextCheckpointTimeInMillis;

    // Need to keep a last seen offset for every topic-partition pair
    private static class TopicPartitionPair {
        public final String topic;
        public final int partition;
        TopicPartitionPair(String inTopic, int inPartition) {
            topic = inTopic;
            partition = inPartition;
        }
        @Override
        public boolean equals(Object o) {
            if (o instanceof TopicPartitionPair) {
                TopicPartitionPair otherPair = (TopicPartitionPair)o;
                return (topic.equals(otherPair.topic) && (partition == otherPair.partition));
            } else {
                return false;
            }
        }
        @Override
        public int hashCode() {
            return Objects.hash(topic, partition);
        }
    }
    Map<TopicPartitionPair, Long> lastSeenOffsets = new HashMap<TopicPartitionPair, Long>();


    // Save the interim state when receiving multiple records in a chain
    String lastTopic = "";
    int lastPartition = -1;
    long lastOffset = -1;
    int nextChunk = 1;
    byte[] chunkBuffer = null;
    boolean disposeUntilNewChunk = true;
    Record lastEndOfChain = null;


    private static class ChunkHeader {
        public String topic;
        public int partition;
        public long offset;
        public int chunk;
        public int totalChunks;
    }

    /**
     * Process a Kinesis record which is one of potentially several chunks that must be
     * reassembled into the original message.
     * @param record the Kinesis record which needs to be unchunked
     */
    private void processChunkRecord(Record record) {

        try {
            // This record is a chunk.  Unwrap from the chunking protocol
            ByteBuffer messageBytes = record.getData();
            int headerSize = messageBytes.getInt();
            byte[] headerBytes = new byte[headerSize];
            messageBytes.get(headerBytes);
            String headerString = new String(headerBytes);
            ChunkHeader header = new ObjectMapper().readValue(headerString, ChunkHeader.class);
            byte[] payloadBytes = new byte[messageBytes.remaining()];
            messageBytes.get(payloadBytes);

            // Run through all the different possibilities of getting expected and unexpected messages
            if (header.chunk == 1) {
                if ((nextChunk != 1) && (isLaterOffset(header.topic, header.partition, header.offset))) {
                    logLossOfData();
                }
                // Initialize the interim state to recieve a new chain of chunks
                chunkBuffer = payloadBytes;
                nextChunk = 2;
                lastTopic = header.topic;
                lastPartition = header.partition;
                lastOffset = header.offset;
                disposeUntilNewChunk = false;

                if (header.totalChunks == 1) {
                    lastEndOfChain = record;
                    processAssembledRecord(lastPartition, lastOffset, chunkBuffer);
                    resetInternalChunkState();
                }
            } else if (disposeUntilNewChunk) {
                // Silently ignore this chunk
            } else if ((lastTopic.equals(header.topic) && (lastPartition == header.partition) && (lastOffset == header.offset))) {
                // This is the right kafka message.  Make sure it is for the right chunk.
                if (header.chunk == nextChunk) {
                    chunkBuffer = ArrayUtils.addAll(chunkBuffer, payloadBytes);
                    ++nextChunk;
                    if (header.totalChunks == header.chunk) {
                        lastEndOfChain = record;
                        processAssembledRecord(lastPartition, lastOffset, chunkBuffer);
                        resetInternalChunkState();
                    }
                } else if (header.chunk < nextChunk) {
                    // duplicate.  Silently ignore.
                } else {
                    // We missed a chunk.
                    logLossOfData();
                    resetInternalChunkState();
                    disposeUntilNewChunk = true;
                }
            } else {
                // A chunk from an unexpected message was received
                if (isLaterOffset(header.topic, header.partition, header.offset)) {
                    logLossOfData();
                }
                resetInternalChunkState();
                disposeUntilNewChunk = true;
            }

            lastSeenOffsets.put(new TopicPartitionPair(header.topic, header.partition), header.offset);
            // TODO: Handle exceptions smartly
            // JSONParseException
            // IOException from JSON parse (huh?)
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private boolean isLaterOffset(String topic, int partition, long offset) {
        TopicPartitionPair pair = new TopicPartitionPair(topic, partition);
        Long lastOffset = lastSeenOffsets.get(pair);
        return ((lastOffset != null) && (lastOffset < offset));
    }

    private void resetInternalChunkState() {
        chunkBuffer = null;
        nextChunk = 1;
        lastTopic = "";
        lastPartition = -1;
        lastOffset = -1;
    }

    private void logLossOfData() {
        LOG.warn("Interruption of message.  Only received " + (nextChunk-1) + " chunks for Message #" +
                lastOffset + " received on topic \"" + lastTopic + "\" partition " + lastPartition + ".");
    }

    @Override
    public void initialize(InitializationInput initializationInput) {
        this.initializationInput = initializationInput;
    }

    @Override
    public void processRecords(ProcessRecordsInput processRecordsInput) {
        LOG.info("Processing " + processRecordsInput.getRecords().size() + " records from " + initializationInput.getShardId());

        // Process records and perform all exception handling.
        processRecordsWithRetries(processRecordsInput.getRecords());

        // Checkpoint once every checkpoint interval.
        if (System.currentTimeMillis() > nextCheckpointTimeInMillis) {
            checkpoint(processRecordsInput.getCheckpointer());
            nextCheckpointTimeInMillis = System.currentTimeMillis() + CHECKPOINT_INTERVAL_MILLIS;
        }
    }

    @Override
    public void shutdown(ShutdownInput shutdownInput) {
        LOG.info("Shutting down record processor for shard: " + initializationInput.getShardId());
        // Important to checkpoint after reaching end of shard, so we can start processing data from child shards.
        if (shutdownInput.getShutdownReason() == ShutdownReason.TERMINATE) {
            checkpoint(shutdownInput.getCheckpointer());
        }
    }

    /** Checkpoint with retries.
     * @param checkpointer
     */
    private void checkpoint(IRecordProcessorCheckpointer checkpointer) {
        LOG.info("Checkpointing shard " + initializationInput.getShardId());
        for (int i = 0; i < NUM_RETRIES; i++) {
            try {
                if (lastEndOfChain != null) {
                    checkpointer.checkpoint(lastEndOfChain);
                }
                break;
            } catch (ShutdownException se) {
                // Ignore checkpoint if the processor instance has been shutdown (fail over).
                LOG.info("Caught shutdown exception, skipping checkpoint.", se);
                break;
            } catch (ThrottlingException e) {
                // Backoff and re-attempt checkpoint upon transient failures
                if (i >= (NUM_RETRIES - 1)) {
                    LOG.error("Checkpoint failed after " + (i + 1) + "attempts.", e);
                    break;
                } else {
                    LOG.info("Transient issue when checkpointing - attempt " + (i + 1) + " of "
                            + NUM_RETRIES, e);
                }
            } catch (InvalidStateException e) {
                // This indicates an issue with the DynamoDB table (check for table, provisioned IOPS).
                LOG.error("Cannot save checkpoint to the DynamoDB table used by the Amazon Kinesis Client Library.", e);
                break;
            }
            try {
                Thread.sleep(BACKOFF_TIME_IN_MILLIS);
            } catch (InterruptedException e) {
                LOG.debug("Interrupted sleep", e);
            }
        }
    }

    /**
     * Process records performing retries as needed. Skip "poison pill" records.
     *
     * @param records Data records to be processed.
     */
    private void processRecordsWithRetries(List<Record> records) {
        for (Record record : records) {
            boolean processedSuccessfully = false;
            for (int i = 0; i < NUM_RETRIES; i++) {
                try {
                    //
                    // Logic to process record goes here.
                    //
                    processChunkRecord(record);

                    processedSuccessfully = true;
                    break;
                } catch (Throwable t) {
                    LOG.warn("Caught throwable while processing record " + record, t);
                }

                // backoff if we encounter an exception.
                try {
                    Thread.sleep(BACKOFF_TIME_IN_MILLIS);
                } catch (InterruptedException e) {
                    LOG.debug("Interrupted sleep", e);
                }
            }

            if (!processedSuccessfully) {
                LOG.error("Couldn't process record " + record + ". Skipping the record.");
            }
        }
    }


    /**
     * Process a single record.
     *
     * @param partition the Kinesis partition this message was received on
     * @param sequenceNum the Kafka offset this message held
     * @param bytes The assembled payload of the original Kafka message
     */
    private void processAssembledRecord(int partition, long sequenceNum, byte[] bytes) {
        String data = null;
        try {
            // For this app, we interpret the payload as UTF-8 chars.
            data = decompress(bytes);

            // Parse JSON
            JsonNode jsonNode = mapper.readValue(data, JsonNode.class);

            // TODO Business Logic

            LOG.info(sequenceNum + ", " + partition + ", " + data);
        } catch (NumberFormatException e) {
            LOG.info("Record does not match sample record format. Ignoring record with data; " + data);
        } catch (IOException e) {
            LOG.error("Malformed data: " + data, e);
        }
    }

    public static String decompress(byte[] compressed) throws IOException {
        ByteArrayInputStream bis = new ByteArrayInputStream(compressed);
        GZIPInputStream gis = new GZIPInputStream(bis);
        BufferedReader br = new BufferedReader(new InputStreamReader(gis, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while((line = br.readLine()) != null) {
            sb.append(line);
        }
        br.close();
        gis.close();
        bis.close();
        return sb.toString();
    }
}
