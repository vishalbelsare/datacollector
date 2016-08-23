/**
 * Copyright 2015 StreamSets Inc.
 *
 * Licensed under the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.streamsets.pipeline.stage.origin.s3;

import com.amazonaws.AbortedException;
import com.amazonaws.AmazonClientException;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.streamsets.pipeline.api.BatchMaker;
import com.streamsets.pipeline.api.Record;
import com.streamsets.pipeline.api.StageException;
import com.streamsets.pipeline.api.impl.Utils;
import com.streamsets.pipeline.config.DataFormat;
import com.streamsets.pipeline.lib.hashing.HashingUtil;
import com.streamsets.pipeline.lib.io.ObjectLengthException;
import com.streamsets.pipeline.lib.io.OverrunException;
import com.streamsets.pipeline.lib.parser.DataParser;
import com.streamsets.pipeline.lib.parser.DataParserException;
import com.streamsets.pipeline.stage.common.DefaultErrorRecordHandler;
import com.streamsets.pipeline.stage.common.ErrorRecordHandler;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class AmazonS3Source extends AbstractAmazonS3Source {

  private final static Logger LOG = LoggerFactory.getLogger(AmazonS3Source.class);
  private static final long DEFAULT_FETCH_SIZE = 1 * 1024 * 1024;
  private static final String BUCKET = "bucket";
  private static final String OBJECT_KEY = "objectKey";
  private static final String OWNER = "owner";
  private static final String SIZE = "size";

  private ErrorRecordHandler errorRecordHandler;
  private DataParser parser;
  private S3Object object;

  public AmazonS3Source(S3ConfigBean s3ConfigBean) {
    super(s3ConfigBean);
  }

  @Override
  protected void initChild(List<ConfigIssue> issues) {
    errorRecordHandler = new DefaultErrorRecordHandler(getContext());
  }

  @Override
  public void destroy() {
    IOUtils.closeQuietly(parser);
    super.destroy();
  }

  @Override
  protected String produce(S3ObjectSummary s3Object, String offset, int maxBatchSize, BatchMaker batchMaker)
    throws StageException, BadSpoolObjectException {
    try {
      if (parser == null) {
        String recordId = s3ConfigBean.s3Config.bucket + s3ConfigBean.s3Config.delimiter + s3Object.getKey();
        if (s3ConfigBean.dataFormat == DataFormat.WHOLE_FILE) {
          handleWholeFileDataFormat(s3Object, recordId);
        } else {
          //Get S3 object instead of stream because we want to call close on the object when we close the
          // parser (and stream)
          if(getContext().isPreview()) {
            long fetchSize = s3Object.getSize() > DEFAULT_FETCH_SIZE ? DEFAULT_FETCH_SIZE : s3Object.getSize();
            if(fetchSize > 0) {
              object = AmazonS3Util.getObjectRange(s3ConfigBean.s3Config.getS3Client(), s3ConfigBean.s3Config.bucket,
                  s3Object.getKey(), fetchSize);
            }  else {
              LOG.warn("Size of object with key '{}' is 0", s3Object.getKey());
              object = AmazonS3Util.getObject(s3ConfigBean.s3Config.getS3Client(), s3ConfigBean.s3Config.bucket,
                  s3Object.getKey());
            }
          } else {
            object = AmazonS3Util.getObject(s3ConfigBean.s3Config.getS3Client(), s3ConfigBean.s3Config.bucket,
                s3Object.getKey());
          }
          parser = s3ConfigBean.dataFormatConfig.getParserFactory().getParser(recordId, object.getObjectContent(),
              offset);
        }
        //we don't use S3 GetObject range capabilities to skip the already process offset because the parsers cannot
        // pick up from a non root doc depth in the case of a single object with records.
      }
      for (int i = 0; i < maxBatchSize; i++) {
        try {
          Record record = parser.parse();
          if (record != null) {
            if(s3ConfigBean.enableMetaData) {
              // if metadata is enabled, set the metadata to the header
              Map<String, Object> metaData = AmazonS3Util.getMetaData(object);
              for(String key : metaData.keySet()) {
                String value = metaData.get(key) == null ? "" : metaData.get(key).toString();
                record.getHeader().setAttribute(key, value);
              }
              // set file name to the header
              record.getHeader().setAttribute("Name", object.getKey());
            }

            batchMaker.addRecord(record);
            offset = parser.getOffset();
          } else {
            parser.close();
            parser = null;
            if (object != null) {
              object.close();
              object = null;
            }
            offset = S3Constants.MINUS_ONE;
            break;
          }
        } catch (ObjectLengthException ex) {
          String exOffset = offset;
          offset = S3Constants.MINUS_ONE;
          errorRecordHandler.onError(Errors.S3_SPOOLDIR_02, s3Object.getKey(), exOffset, ex);
        }
      }
    } catch (AmazonClientException e) {
      LOG.error("Error processing object with key '{}' offset '{}'", s3Object.getKey(), offset, e);
      throw new StageException(Errors.S3_SPOOLDIR_25, e.toString(), e);
    } catch (IOException | DataParserException ex) {
      if(ex.getCause() instanceof AbortedException) {
        //If the pipeline was stopped, the amazon s3 client thread catches the interrupt and throws aborted exception
        //do not treat this as an error. Instead produce what ever you have and move one.

      } else {
        offset = S3Constants.MINUS_ONE;
        String exOffset;
        if (ex instanceof OverrunException) {
          exOffset = String.valueOf(((OverrunException) ex).getStreamOffset());
        } else {
          try {
            exOffset = (parser != null) ? parser.getOffset() : S3Constants.MINUS_ONE;
          } catch (IOException ex1) {
            LOG.warn("Could not get the object offset to report with error, reason: {}", ex1.toString(), ex);
            exOffset = S3Constants.MINUS_ONE;
          }
        }

        switch (getContext().getOnErrorRecord()) {
          case DISCARD:
            break;
          case TO_ERROR:
            // we failed to produce a record, which leaves the input file in an unknown state. all we can do here is
            // throw an exception.
            throw new BadSpoolObjectException(s3Object.getKey(), exOffset, ex);
          case STOP_PIPELINE:
            getContext().reportError(Errors.S3_SPOOLDIR_03, s3Object.getKey(), exOffset, ex.toString(), ex);
            throw new StageException(Errors.S3_SPOOLDIR_03, s3Object.getKey(), exOffset, ex.toString(), ex);
          default:
            throw new IllegalStateException(Utils.format("Unknown OnError value '{}'",
              getContext().getOnErrorRecord(), ex));
        }
      }
    } finally {
      if (S3Constants.MINUS_ONE.equals(offset)) {
        if (parser != null) {
          try {
            parser.close();
            parser = null;
          } catch (IOException ex) {
            LOG.debug("Exception while closing parser : '{}'", ex.toString(), ex);
          }
        }
        if (object != null) {
          try {
            object.close();
            object = null;
          } catch (IOException ex) {
            LOG.debug("Exception while closing S3 object : '{}'", ex.toString(), ex);
          }
        }
      }
    }
    return offset;
  }

  //For whole file we do not care whether it is a preview or not,
  //as the record is just the metadata along with file ref.
  private void handleWholeFileDataFormat(S3ObjectSummary s3ObjectSummary, String recordId) throws DataParserException, IOException {
    S3Object partialS3ObjectForMetadata = null;
    try {
      //partialObject with fetchSize 1 byte.
      //This is mostly used for extracting metadata and such.
      partialS3ObjectForMetadata = AmazonS3Util.getObjectRange(
          s3ConfigBean.s3Config.getS3Client(),
          s3ConfigBean.s3Config.bucket,
          s3ObjectSummary.getKey(),
          1
      );
      S3FileRef.Builder s3FileRefBuilder = new S3FileRef.Builder()
          .s3Client(s3ConfigBean.s3Config.getS3Client())
          .s3ObjectSummary(s3ObjectSummary)
          .bufferSize(s3ConfigBean.dataFormatConfig.wholeFileMaxObjectLen)
          .createMetrics(true)
          .totalSizeInBytes(s3ObjectSummary.getSize());
      if (s3ConfigBean.dataFormatConfig.verifyChecksum) {
        s3FileRefBuilder.verifyChecksum(true)
            .checksumAlgorithm(HashingUtil.HashType.MD5)
            //128 bit hex encoded md5 checksum.
            .checksum(partialS3ObjectForMetadata.getObjectMetadata().getETag());
      }
      Map<String, Object> metadata = AmazonS3Util.getMetaData(partialS3ObjectForMetadata);
      metadata.put(BUCKET, s3ObjectSummary.getBucketName());
      metadata.put(OBJECT_KEY, s3ObjectSummary.getKey());
      metadata.put(OWNER, s3ObjectSummary.getOwner());
      metadata.put(SIZE, s3ObjectSummary.getSize());
      parser = s3ConfigBean.dataFormatConfig.getParserFactory().getParser(recordId, metadata, s3FileRefBuilder.build());
    } finally {
      if (partialS3ObjectForMetadata != null) {
        partialS3ObjectForMetadata.close();
      }
    }
  }
}
