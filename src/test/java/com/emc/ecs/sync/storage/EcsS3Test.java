/*
 * Copyright 2013-2017 EMC Corporation. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 * http://www.apache.org/licenses/LICENSE-2.0.txt
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.emc.ecs.sync.storage;

import com.emc.ecs.sync.EcsSync;
import com.emc.ecs.sync.config.SyncConfig;
import com.emc.ecs.sync.config.SyncOptions;
import com.emc.ecs.sync.config.storage.EcsS3Config;
import com.emc.ecs.sync.model.Checksum;
import com.emc.ecs.sync.model.ObjectMetadata;
import com.emc.ecs.sync.model.SyncObject;
import com.emc.ecs.sync.storage.file.AbstractFilesystemStorage;
import com.emc.ecs.sync.storage.s3.EcsS3Storage;
import com.emc.ecs.sync.test.TestConfig;
import com.emc.ecs.sync.util.EnhancedThreadPoolExecutor;
import com.emc.ecs.sync.util.RandomInputStream;
import com.emc.object.Protocol;
import com.emc.object.s3.S3Client;
import com.emc.object.s3.S3Config;
import com.emc.object.s3.S3Exception;
import com.emc.object.s3.S3ObjectMetadata;
import com.emc.object.s3.bean.ListObjectsResult;
import com.emc.object.s3.bean.S3Object;
import com.emc.object.s3.jersey.S3JerseyClient;
import com.emc.object.util.ChecksumAlgorithm;
import com.emc.object.util.ChecksummedInputStream;
import com.emc.object.util.RestUtil;
import com.emc.object.util.RunningChecksum;
import com.emc.rest.util.StreamUtil;
import org.junit.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.bind.DatatypeConverter;
import java.io.*;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Properties;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

public class EcsS3Test {
    private static final Logger log = LoggerFactory.getLogger(EcsS3Test.class);

    private String bucketName = "ecs-sync-ecs-s3-target-test-bucket";
    private S3Client s3;
    private TestStorage testStorage;
    private EcsS3Storage storage;

    @Before
    public void setup() throws Exception {
        Properties syncProperties = TestConfig.getProperties();
        String endpoint = syncProperties.getProperty(TestConfig.PROP_S3_ENDPOINT);
        final String accessKey = syncProperties.getProperty(TestConfig.PROP_S3_ACCESS_KEY_ID);
        final String secretKey = syncProperties.getProperty(TestConfig.PROP_S3_SECRET_KEY);
        final boolean useVHost = Boolean.valueOf(syncProperties.getProperty(TestConfig.PROP_S3_VHOST));
        Assume.assumeNotNull(endpoint, accessKey, secretKey);
        final URI endpointUri = new URI(endpoint);

        S3Config s3Config;
        if (useVHost) s3Config = new S3Config(endpointUri);
        else s3Config = new S3Config(Protocol.valueOf(endpointUri.getScheme().toUpperCase()), endpointUri.getHost());
        s3Config.withPort(endpointUri.getPort()).withUseVHost(useVHost).withIdentity(accessKey).withSecretKey(secretKey);

        s3 = new S3JerseyClient(s3Config);

        try {
            s3.createBucket(bucketName);
        } catch (S3Exception e) {
            if (!e.getErrorCode().equals("BucketAlreadyExists")) throw e;
        }

        testStorage = new TestStorage();
        testStorage.withConfig(new com.emc.ecs.sync.config.storage.TestConfig()).withOptions(new SyncOptions());

        EcsS3Config config = new EcsS3Config();
        config.setProtocol(com.emc.ecs.sync.config.Protocol.valueOf(endpointUri.getScheme().toLowerCase()));
        config.setHost(endpointUri.getHost());
        config.setPort(endpointUri.getPort());
        config.setEnableVHosts(useVHost);
        config.setAccessKey(accessKey);
        config.setSecretKey(secretKey);
        config.setBucketName(bucketName);

        storage = new EcsS3Storage();
        storage.setConfig(config);
        storage.setOptions(new SyncOptions());
        storage.configure(testStorage, null, storage);
    }

    @After
    public void teardown() {
        if (testStorage != null) testStorage.close();
        if (storage != null) storage.close();
        deleteBucket(s3, bucketName);
    }

    @Test
    public void testNormalUpload() throws Exception {
        String key = "normal-upload";
        long size = 512 * 1024; // 512KiB
        InputStream stream = new RandomInputStream(size);
        SyncObject object = new SyncObject(storage, key, new ObjectMetadata().withContentLength(size), stream, null);

        storage.updateObject(key, object);

        // proper ETag means no MPU was performed
        Assert.assertEquals(object.getMd5Hex(true).toUpperCase(), s3.getObjectMetadata(bucketName, key).getETag().toUpperCase());
    }

    @Test
    public void testCifsEcs() throws Exception {
        String sBucket = "ecs-sync-cifs-ecs-test";
        String key = "empty-file";
        s3.createBucket(sBucket);
        s3.putObject(sBucket, key, (Object) null, null);

        try {
            EcsS3Config source = new EcsS3Config();
            source.setProtocol(storage.getConfig().getProtocol());
            source.setHost(storage.getConfig().getHost());
            source.setPort(storage.getConfig().getPort());
            source.setEnableVHosts(storage.getConfig().isEnableVHosts());
            source.setAccessKey(storage.getConfig().getAccessKey());
            source.setSecretKey(storage.getConfig().getSecretKey());
            source.setBucketName(sBucket);
            source.setApacheClientEnabled(true);

            SyncConfig config = new SyncConfig().withSource(source);
            config.getOptions().setRetryAttempts(0); // disable retries for brevity

            EcsSync sync = new EcsSync();
            sync.setSyncConfig(config);
            sync.setTarget(storage);
            sync.run();

            Assert.assertEquals(0, sync.getStats().getObjectsFailed());
            Assert.assertEquals(1, sync.getStats().getObjectsComplete());
            Assert.assertNotNull(s3.getObjectMetadata(bucketName, key));
        } finally {
            s3.deleteObject(sBucket, key);
            s3.deleteBucket(sBucket);
        }
    }

    @Test
    public void testCrazyCharacters() throws Exception {
        String[] keys = {
                "foo!@#$%^&*()-_=+",
                "bar\u00a1\u00bfbar",
                "?????????unicode",
                "baz\u0007bim",
                "bim\u0006-boozle"
        };

        String sBucket = "ecs-sync-crazy-char-test";

        s3.createBucket(sBucket);
        for (String key : keys) {
            s3.putObject(sBucket, key, (Object) null, null);
        }

        try {
            EcsS3Config s3Config = new EcsS3Config();
            s3Config.setProtocol(storage.getConfig().getProtocol());
            s3Config.setHost(storage.getConfig().getHost());
            s3Config.setPort(storage.getConfig().getPort());
            s3Config.setEnableVHosts(storage.getConfig().isEnableVHosts());
            s3Config.setAccessKey(storage.getConfig().getAccessKey());
            s3Config.setSecretKey(storage.getConfig().getSecretKey());
            s3Config.setBucketName(sBucket);
            s3Config.setApacheClientEnabled(true);
            s3Config.setUrlEncodeKeys(true);

            SyncConfig config = new SyncConfig().withSource(s3Config).withTarget(new com.emc.ecs.sync.config.storage.TestConfig().withDiscardData(false));
            config.getOptions().withVerify(true).setRetryAttempts(0); // disable retries for brevity

            EcsSync sync = new EcsSync();
            sync.setSyncConfig(config);
            sync.run();

            Assert.assertEquals(0, sync.getStats().getObjectsFailed());
            Assert.assertEquals(keys.length, sync.getStats().getObjectsComplete());
        } finally {
            for (String key : keys) {
                s3.deleteObject(sBucket, key);
            }
            s3.deleteBucket(sBucket);
        }
    }

    @Ignore // only perform this test on a co-located ECS!
    @Test
    public void testVeryLargeUploadStream() throws Exception {
        String key = "large-stream-upload";
        long size = 512L * 1024 * 1024 + 10; // 512MB + 10 bytes
        InputStream stream = new RandomInputStream(size);

        SyncObject object = new SyncObject(testStorage, key, new ObjectMetadata().withContentLength(size), stream, null);

        storage.updateObject(key, object);

        // hyphen denotes an MPU
        Assert.assertTrue(s3.getObjectMetadata(bucketName, key).getETag().contains("-"));

        // verify bytes read from source
        // first wait a tick so the perf counter has at least one interval
        Thread.sleep(1000);
        Assert.assertEquals(size, object.getBytesRead());
        Assert.assertTrue(testStorage.getReadRate() > 0);

        // need to read the entire object since we can't use the ETag
        InputStream objectStream = s3.getObject(bucketName, key).getObject();
        ChecksummedInputStream md5Stream = new ChecksummedInputStream(objectStream, new RunningChecksum(ChecksumAlgorithm.MD5));
        byte[] buffer = new byte[128 * 1024];
        int c;
        do {
            c = md5Stream.read(buffer);
        } while (c >= 0);
        md5Stream.close();

        Assert.assertEquals(object.getMd5Hex(true).toUpperCase(), md5Stream.getChecksum().getHexValue().toUpperCase());
    }

    @Ignore // only perform this test on a co-located ECS!
    @Test
    public void testVeryLargeUploadFile() throws Exception {
        String key = "large-file-upload";
        long size = 512L * 1024 * 1024 + 10; // 512MB + 10 bytes
        InputStream stream = new RandomInputStream(size);

        // create temp file
        File tempFile = File.createTempFile(key, null);
        tempFile.deleteOnExit();
        StreamUtil.copy(stream, new FileOutputStream(tempFile), size);

        SyncObject object = new SyncObject(testStorage, key, new ObjectMetadata().withContentLength(size), new FileInputStream(tempFile), null);
        object.setProperty(AbstractFilesystemStorage.PROP_FILE, tempFile);

        storage.updateObject(key, object);

        // hyphen denotes an MPU
        Assert.assertTrue(s3.getObjectMetadata(bucketName, key).getETag().contains("-"));

        // verify bytes read from source
        // first wait a tick so the perf counter has at least one interval
        Thread.sleep(1000);
        Assert.assertEquals(size, object.getBytesRead());
        Assert.assertTrue(testStorage.getReadRate() > 0);

        // need to read the entire object since we can't use the ETag
        InputStream objectStream = s3.getObject(bucketName, key).getObject();
        ChecksummedInputStream md5Stream = new ChecksummedInputStream(objectStream, new RunningChecksum(ChecksumAlgorithm.MD5));
        byte[] buffer = new byte[128 * 1024];
        int c;
        do {
            c = md5Stream.read(buffer);
        } while (c >= 0);
        md5Stream.close();

        Assert.assertEquals(object.getMd5Hex(true).toUpperCase(), md5Stream.getChecksum().getHexValue().toUpperCase());
    }

    @Test
    public void testInvalidContentType() {
        String key = "invalid-content-type";

        SyncObject object = new SyncObject(testStorage, key,
                new ObjectMetadata().withContentLength(0).withContentType("foo"), new ByteArrayInputStream(new byte[0]),
                null);

        storage.updateObject(key, object);

        Assert.assertEquals(RestUtil.DEFAULT_CONTENT_TYPE, s3.getObjectMetadata(bucketName, key).getContentType());
    }

    public static void deleteBucket(final S3Client s3, final String bucket) {
        try {
            EnhancedThreadPoolExecutor executor = new EnhancedThreadPoolExecutor(30,
                    new LinkedBlockingDeque<Runnable>(2100), "object-deleter");

            ListObjectsResult listing = null;
            do {
                if (listing == null) listing = s3.listObjects(bucket);
                else listing = s3.listMoreObjects(listing);

                for (final S3Object summary : listing.getObjects()) {
                    executor.blockingSubmit(new Runnable() {
                        @Override
                        public void run() {
                            s3.deleteObject(bucket, summary.getKey());
                        }
                    });
                }
            } while (listing.isTruncated());

            executor.shutdown();
            executor.awaitTermination(2, TimeUnit.MINUTES);

            s3.deleteBucket(bucket);
        } catch (RuntimeException e) {
            log.error("could not delete bucket " + bucket, e);
        } catch (InterruptedException e) {
            log.error("timed out while waiting for objects to be deleted");
        }
    }

    private boolean isRetentionInRange(long expectedValue, long retentionPeriod) {
        return Math.abs(expectedValue - retentionPeriod) <= 2;
    }

    @Test
    public void testUploadWithRetention() throws Exception {
        try {
            testStorage = new TestStorage();
            testStorage.withConfig(new com.emc.ecs.sync.config.storage.TestConfig()).
                    withOptions(new SyncOptions().withSyncRetentionExpiration(true));

            EcsS3Storage retentionStorage = new EcsS3Storage();
            retentionStorage.setConfig(storage.getConfig());
            retentionStorage.setOptions(new SyncOptions().withSyncRetentionExpiration(true));
            retentionStorage.configure(testStorage, null, retentionStorage);

            String key = "retention-upload";
            long size = 512 * 1024; // 512KiB
            InputStream stream = new RandomInputStream(size);

            long currentDate = System.currentTimeMillis();
            Date retentionTime = new Date(currentDate + 10 * 1000); // 10 sec
            SyncObject object = new SyncObject(retentionStorage, key,
                    new ObjectMetadata().withContentLength(size).withRetentionEndDate(retentionTime), stream, null);
            retentionStorage.updateObject(key, object);
            long retentionPeriod = TimeUnit.MILLISECONDS.toSeconds(retentionTime.getTime() - currentDate);
            Assert.assertTrue(isRetentionInRange(retentionPeriod, s3.getObjectMetadata(bucketName, key).getRetentionPeriod()));
        } finally {
            // in case any assertions fail or there is any other error, we need to wait for retention to expire before trying to delete the object
            Thread.sleep(15 * 1000);
        }
    }

    @Test
    public void testsyncMetaFromS3MetaWithRetention() throws Exception {
        EcsS3Storage retentionStorage = new EcsS3Storage();
        retentionStorage.setConfig(storage.getConfig());
        retentionStorage.setOptions(new SyncOptions().withSyncRetentionExpiration(true));

        S3ObjectMetadata s3ObjectMetadata = new S3ObjectMetadata();
        s3ObjectMetadata.withContentType("")
                .withContentLength(0l)
                .withRetentionPeriod(100l)  // 100 secs
                .withCacheControl("")
                .withContentDisposition("")
                .withContentEncoding("")
                .withHttpExpires(new Date());
        s3ObjectMetadata.setExpirationDate(new Date());
        // set mtime to 10 seconds ago - this leaves 90 seconds left of retention
        s3ObjectMetadata.setLastModified(new Date(System.currentTimeMillis() - 10000));
        s3ObjectMetadata.setUserMetadata(new HashMap<>());

        Method privateSyncMetaFromS3Meta = retentionStorage.getClass().getDeclaredMethod("syncMetaFromS3Meta", S3ObjectMetadata.class);
        privateSyncMetaFromS3Meta.setAccessible(true);
        ObjectMetadata objectMetadata = (ObjectMetadata) privateSyncMetaFromS3Meta.invoke(retentionStorage, s3ObjectMetadata);
        Assert.assertTrue(isRetentionInRange(90l, TimeUnit.MILLISECONDS.toSeconds(objectMetadata.getRetentionEndDate().getTime() - System.currentTimeMillis())));
    }

    @Test
    public void testSendContentMd5() {
        String goodKey = "valid-content-md5";
        String badKey = "invalid-content-md5";
        String data = "Dummy data to generate an MD5.";

        String goodMd5 = DatatypeConverter.printBase64Binary(DatatypeConverter.parseHexBinary("d731e47a8731774b9b88ba89dbb1c7ec"));
        String badMd5 = DatatypeConverter.printBase64Binary(DatatypeConverter.parseHexBinary("e731e47a8731774b9b88ba89dbb1c7ec"));

        SyncObject goodObject = new SyncObject(testStorage, goodKey,
                new ObjectMetadata().withContentLength(data.length()).withContentType("text/plain").withChecksum(new Checksum("MD5", goodMd5)),
                new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8)),
                null);
        SyncObject badObject = new SyncObject(testStorage, badKey,
                new ObjectMetadata().withContentLength(data.length()).withContentType("text/plain").withChecksum(new Checksum("MD5", badMd5)),
                new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8)),
                null);

        // this should work
        storage.updateObject(goodKey, goodObject);
        try {
            // but this should fail
            storage.updateObject(badKey, badObject);
            Assert.fail("sending bad checksum should fail the write");
        } catch (RuntimeException e) {
            Assert.assertTrue(e.getCause() instanceof S3Exception);
            Assert.assertEquals(400, ((S3Exception) e.getCause()).getHttpCode());
            Assert.assertTrue(((S3Exception) e.getCause()).getMessage().contains("Content-MD5"));
        }

        // make sure bad object does not exist
        try {
            s3.getObjectMetadata(bucketName, badKey);
        } catch (S3Exception e) {
            Assert.assertEquals(404, e.getHttpCode());
        }
    }
}
