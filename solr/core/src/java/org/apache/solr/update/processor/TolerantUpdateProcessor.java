/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.update.processor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;


import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.CharsRefBuilder;
import org.apache.solr.cloud.ZkController;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.common.cloud.DocCollection;
import org.apache.solr.common.cloud.Slice;
import org.apache.solr.common.params.ShardParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.schema.SchemaField;
import org.apache.solr.update.AddUpdateCommand;
import org.apache.solr.update.SolrCmdDistributor.Error;
import org.apache.solr.update.processor.DistributedUpdateProcessor.DistribPhase;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 
 * <p> 
 * Suppresses errors for individual add/delete commands within a batch.
 * Instead, all errors are logged and the batch continues. The client
 * will receive a 200 response, but gets a list of errors (keyed by
 * unique key) unless <code>maxErrors</code> is reached. 
 * If <code>maxErrors</code> occur, the first exception caught will be re-thrown, 
 * Solr will respond with 5XX or 4XX (depending on the exception) and
 * it won't finish processing the batch. This means that the last docs
 * in the batch may not be added in this case even if they are valid. 
 * </p>
 * 
 * <p>
 * NOTE: In cloud based collections, this processor expects to <b>NOT</b> be used on {@link DistribPhase#FROMLEADER} 
 * requests (because any successes that occur locally on the leader are considered successes even if there is some 
 * subsequent error on a replica).  {@link TolerantUpdateProcessorFactory} will short circut it away in those 
 * requests.
 * </p>
 * 
 * @see TolerantUpdateProcessorFactory
 */
public class TolerantUpdateProcessor extends UpdateRequestProcessor {
  private static final Logger log = LoggerFactory.getLogger(TolerantUpdateProcessor.class);
  /**
   * String to be used as document key in the response if a real ID can't be determined
   */
  private static final String UNKNOWN_ID = "(unknown)"; // nocommit: fail hard and fast if no uniqueKey

  /**
   * Response Header
   */
  private final NamedList<Object> header;
  
  /**
   * Number of errors this UpdateRequestProcessor will tolerate. If more then this occur, 
   * the original exception will be thrown, interrupting the processing of the document
   * batch
   */
  private final int maxErrors;
  
  private final SolrQueryRequest req;
  private final SolrQueryResponse rsp; // nocommit: needed?
  private ZkController zkController;

  /**
   * Known errors that occurred in this batch, in order encountered (may not be the same as the 
   * order the commands were originally executed in due to the async distributed updates).
   */
  private final List<KnownErr> knownErrors = new ArrayList<KnownErr>();

  private final FirstErrTracker firstErrTracker = new FirstErrTracker();
  private final DistribPhase distribPhase;

  public TolerantUpdateProcessor(SolrQueryRequest req, SolrQueryResponse rsp, UpdateRequestProcessor next, int maxErrors, DistribPhase distribPhase) {
    super(next);
    assert maxErrors >= 0;
      
    this.rsp = rsp; // nocommit: needed?
    header = rsp.getResponseHeader();
    this.maxErrors = maxErrors;
    this.req = req;
    this.distribPhase = distribPhase;
    assert ! DistribPhase.FROMLEADER.equals(distribPhase);
    
    this.zkController = this.req.getCore().getCoreDescriptor().getCoreContainer().getZkController();

    // nocommit: assert existence of uniqueKey & record for future processAdd+processAddError calls
  }
  
  @Override
  public void processAdd(AddUpdateCommand cmd) throws IOException {
    boolean isLeader = isLeader(cmd);
    BytesRef id = null;
    
    try {
      // force AddUpdateCommand to validate+cache the id before proceeding
      id = cmd.getIndexedId();
      
      super.processAdd(cmd);

    } catch (Throwable t) { // nocommit: OOM trap
      firstErrTracker.caught(t);
      
      if (isLeader || distribPhase.equals(DistribPhase.NONE)) {
        processAddError(getPrintableId(id, cmd.getReq().getSchema().getUniqueKeyField()), t);
        if (knownErrors.size() > maxErrors) {
          firstErrTracker.throwFirst();
        }
      } else {
        firstErrTracker.throwFirst();
      }
    }
  }
  
  
  // nocommit: what about processCommit and processDelete and other UP methods? ...
  // nocommit: ...at a minimum use firstErrTracker to catch & rethrow so finish can annotate

  // nocommit: refactor this method away
  protected void processAddError(CharSequence id, Throwable error) {
    processAddError(id, error.getMessage());
  }
  
  /** 
   * Logs an error for the given id, and buffers it up to be 
   * included in the response header 
   */
  protected void processAddError(CharSequence id, CharSequence error) {
  // nocommit: need refactor the KnownErr wrapping up so this method can handle deletes & commits as well
    knownErrors.add(new KnownErr(CmdType.ADD, id.toString(), error.toString()));
  }

  @Override
  public void finish() throws IOException {

    // even if processAdd threw an error, this.finish() is still called and we might have additional
    // errors from other remote leaders that we need to check for from the finish method of downstream processors
    // (like DUP)
    
    try {
      super.finish();
    } catch (DistributedUpdateProcessor.DistributedUpdatesAsyncException duae) {
      firstErrTracker.caught(duae);

      // adjust out stats based on the distributed errors
      for (Error error : duae.errors) {
        // we can't trust the req info from the Error, because multiple original requests might have been
        // lumped together
        //
        // instead we trust the metadata that the TolerantUpdateProcessor running on the remote node added
        // to the exception when it failed.
        if ( ! (error.e instanceof SolrException) ) {
          log.error("async update exception is not SolrException, no metadata to process", error.e);
          continue;
        }
        SolrException remoteErr = (SolrException) error.e;
        NamedList<String> remoteErrMetadata = remoteErr.getMetadata();

        if (null == remoteErrMetadata) {
          log.warn("remote error has no metadata to aggregate: " + remoteErr.getMessage(), remoteErr);
          continue;
        }
        
        for (int i = 0; i < remoteErrMetadata.size(); i++) {
          KnownErr err = KnownErr.parseMetadataIfKnownErr(remoteErrMetadata.getName(i),
                                                          remoteErrMetadata.getVal(i));
          if (null == err) {
            // some metadata unrelated to this update processor
            continue;
          }
          
          if (err.type.equals(CmdType.ADD)) { // nocommit: generalize this to work with any CmdType
            processAddError(err.id, err.errorValue);
          } else {
            log.error("found remote error metadata we can't handle key: " + err);
            assert false : "found remote error metadata we can't handle key: " + err;
          }
        }
      }
    }

    // good or bad populate the response header
    if (0 < knownErrors.size()) { // nocommit: we should just always set errors, even if empty?
      
      header.add("numErrors", knownErrors.size()); // nocommit: eliminate from response, client can count
      header.add("errors", KnownErr.formatForResponseHeader(knownErrors));
    } else {
      header.add("numErrors", 0); // nocommit: eliminate from response, client can count
    }

    // annotate any error that might be thrown (or was already thrown)
    firstErrTracker.annotate(knownErrors);

    // decide if we have hit a situation where we know an error needs to be thrown.
    
    if ((DistribPhase.TOLEADER.equals(distribPhase) ? 0 : maxErrors) < knownErrors.size()) {
      // NOTE: even if maxErrors wasn't exceeeded, we need to throw an error when we have any errors if we're
      // a leader that was forwarded to by another node so that the forwarding node knows we encountered some
      // problems and can aggregate the results

      firstErrTracker.throwFirst();
    }
  }

  /**
   * Returns the output of {@link org.apache.solr.schema.FieldType#
   * indexedToReadable(BytesRef, CharsRefBuilder)} of the field
   * type of the uniqueKey on the {@link BytesRef} passed as parameter.
   * <code>ref</code> should be the indexed representation of the id and
   * <code>field</code> should be the uniqueKey schema field. If any of
   * the two parameters is null this method will return {@link #UNKNOWN_ID}
   */
  private String getPrintableId(BytesRef ref, SchemaField field) {
    if(ref == null || field == null) {
      return UNKNOWN_ID; // nocommit: fail hard and fast
    }
    return field.getType().indexedToReadable(ref, new CharsRefBuilder()).toString();
  }

  // nocommit: javadocs ... also: sanity check this method is even accurate
  private boolean isLeader(AddUpdateCommand cmd) {
    if(!cmd.getReq().getCore().getCoreDescriptor().getCoreContainer().isZooKeeperAware())
      return true;
    String collection = cmd.getReq().getCore().getCoreDescriptor().getCollectionName();
    DocCollection coll = zkController.getClusterState().getCollection(collection);

    SolrParams params = req.getParams();
    String route = req.getParams().get(ShardParams._ROUTE_);
    Slice slice = coll.getRouter().getTargetSlice(cmd.getHashableId(), cmd.getSolrInputDocument(), route, params, coll);
    return slice.getLeader().getName().equals(req.getCore().getCoreDescriptor().getCloudDescriptor().getCoreNodeName());

  }

  /**
   * Simple helper class for "tracking" any exceptions encountered.
   * 
   * Only remembers the "first" exception encountered, and wraps it in a SolrException if needed, so that 
   * it can later be annotated with the metadata our users expect and re-thrown.
   *
   * NOTE: NOT THREAD SAFE
   */
  private static final class FirstErrTracker {

    
    SolrException first = null;
    boolean thrown = false;
    
    public FirstErrTracker() {
      /* NOOP */
    }
    
    /** 
     * Call this method immediately anytime an exception is caught from a down stream method -- 
     * even if you are going to ignore it (for now).  If you plan to rethrow the Exception, use 
     * {@link #throwFirst} instead.
     */
    public void caught(Throwable t) {    // nocommit: switch to just Exception?
      assert null != t;
      if (null == first) {
        if (t instanceof SolrException) {
          first = (SolrException)t;
        } else {
          first = new SolrException(ErrorCode.SERVER_ERROR, "Tolerantly Caught Exception: " + t.getMessage(), t);
        }
      }
    }
    
    /** 
     * Call this method in place of any situation where you would normally (re)throw an exception 
     * (already passed to the {@link #caught} method because maxErrors was exceeded
     * is exceed.
     *
     * This method will keep a record that this update processor has already thrown the exception, and do 
     * nothing on future calls, so subsequent update processor methods can update the metadata but won't 
     * inadvertantly re-throw this (or any other) cascading exception by mistake.
     */
    public void throwFirst() throws SolrException {
      assert null != first : "caught was never called?";
      if (! thrown) {
        thrown = true;
        throw first;
      }
    }
    
    /** 
     * Annotates the first exception (which may already have been thrown, or be thrown in the future) with 
     * the metadata from this update processor.  For use in {@link TolerantUpdateProcessor#finish}
     */
    public void annotate(List<KnownErr> errors) {

      if (null == first) {
        return; // no exception to annotate
      }
      
      assert null != errors : "how do we have an exception to annotate w/o any errors?";
      
      NamedList<String> firstErrMetadata = first.getMetadata();
      if (null == firstErrMetadata) { // obnoxious
        firstErrMetadata = new NamedList<String>();
        first.setMetadata(firstErrMetadata);
      }

      for (KnownErr ke : errors) {
        firstErrMetadata.add(ke.getMetadataKey(), ke.getMetadataValue());
      }
    }
    
    
    /** The first exception that was thrown (or may be thrown) whose metadata can be annotated. */
    public SolrException getFirst() {
      return first;
    }
    
  }

  /**
   * Helper class for dealing with SolrException metadata (String) keys 
   */
  public static final class KnownErr {
    
    private final static String META_PRE =  TolerantUpdateProcessor.class.getName() + "--";
    private final static int META_PRE_LEN = META_PRE.length();

    /** returns a map of simple objects suitable for putting in a SolrQueryResponse */
    public static List<SimpleOrderedMap<String>> formatForResponseHeader(List<KnownErr> errs) {
      List<SimpleOrderedMap<String>> result = new ArrayList<>(errs.size());
      for (KnownErr e : errs) {
        SimpleOrderedMap<String> entry = new SimpleOrderedMap<String>();
        entry.add("type", e.type.toString());
        entry.add("id", e.id);
        entry.add("message", e.errorValue);
        result.add(entry);
      }
      return result;
    }
    
    /** returns a KnownErr instance if this metadataKey is one we care about, else null */
    public static KnownErr parseMetadataIfKnownErr(String metadataKey, String metadataVal) {
      if (! metadataKey.startsWith(META_PRE)) {
        return null; // not a key we care about
      }
      final int typeEnd = metadataKey.indexOf(':', META_PRE_LEN);
      assert 0 < typeEnd; // nocommit: better error handling
      return new KnownErr(CmdType.valueOf(metadataKey.substring(META_PRE_LEN, typeEnd)),
                          metadataKey.substring(typeEnd+1), metadataVal);
    }

    public final CmdType type;
    /** may be null depending on type */
    public final String id;
    public final String errorValue; // nocommit: refactor: rename errMessage?
    
    public KnownErr(CmdType type, String id, String errorValue) {
      this.type = type;
      assert null != type;
      
      assert null != id;
      this.id = id;
      
      assert null != errorValue;
      this.errorValue = errorValue;
    }
    
    public String getMetadataKey() {
      return META_PRE + type + ":" + id;
    }
    public String getMetadataValue() {
      return errorValue.toString();
    }
    public String toString() {
      return getMetadataKey() + "=>" + getMetadataValue();
    }
    public int hashCode() {
      int h = this.getClass().hashCode();
      h = h * 31 + type.hashCode();
      h = h * 31 + id.hashCode();
      h = h * 31 + errorValue.hashCode();
      return h;
    }
    public boolean equals(Object o) {
      if (o instanceof KnownErr) {
        KnownErr that = (KnownErr)o;
        return that.type.equals(this.type)
          && that.id.equals(this.id)
          && that.errorValue.equals(this.errorValue);
      }
      return false;
    }
  }
  
  /**
   * Helper class for dealing with SolrException metadata (String) keys 
   */
  public static enum CmdType {
    ADD, DELID, DELQ; // nocommit: others supported types? (commit?) ..

    // if we add support for things like commit, parsing/toString/hashCode logic
    // needs to be smarter to account for 'id' being null ... "usesId" should be a prop of enum instances
  }
}
