package com.washingtonpost.kinesis.sample;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.auth.STSAssumeRoleSessionCredentialsProvider;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.v2.IRecordProcessorFactory;
import com.amazonaws.services.kinesis.clientlibrary.lib.worker.KinesisClientLibConfiguration;
import com.amazonaws.services.kinesis.clientlibrary.lib.worker.Worker;
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClientBuilder;

import java.net.InetAddress;
import java.util.UUID;

/**
 * Created by findleyr on 10/4/16.
 */
public class Application {

    private final static String APPLICATION_NAME = System.getenv("APPLICATION_NAME");
    private final static String KINESIS_STREAM_NAME = System.getenv("KINESIS_STREAM_NAME");
    private final static String AWS_ROLE_ARN = System.getenv("AWS_ROLE_ARN");
    private final static String AWS_ROLE_SESSION_NAME = System.getenv("AWS_ROLE_SESSION_NAME");

    public static void main(String[] args) throws Exception {
        String workerId = InetAddress.getLocalHost().getCanonicalHostName() + ":" + UUID.randomUUID();
        AWSCredentialsProvider defaultProvider = new DefaultAWSCredentialsProviderChain();
        AWSCredentialsProvider kinesisProvider = (new STSAssumeRoleSessionCredentialsProvider.Builder(AWS_ROLE_ARN, AWS_ROLE_SESSION_NAME)).withLongLivedCredentialsProvider(defaultProvider).build();
        AWSCredentialsProvider dynamoDBProvider = defaultProvider;
        AWSCredentialsProvider cloudWatchProvider = defaultProvider;

        final KinesisClientLibConfiguration config = new KinesisClientLibConfiguration(APPLICATION_NAME, KINESIS_STREAM_NAME,
                kinesisProvider, dynamoDBProvider, cloudWatchProvider, workerId);
        final IRecordProcessorFactory recordProcessorFactory = new RecordProcessorFactory();
        final Worker worker = new Worker.Builder()
                .recordProcessorFactory(recordProcessorFactory)
                .config(config)
                .build();

        System.out.printf("Running %s to process stream %s as worker %s...\n",
                APPLICATION_NAME,
                KINESIS_STREAM_NAME,
                workerId);

        int exitCode = 0;
        try {
            worker.run();
        } catch (Throwable t) {
            System.err.println("Caught throwable while processing data.");
            t.printStackTrace();
            exitCode = 1;
        }
        System.exit(exitCode);
    }
}
