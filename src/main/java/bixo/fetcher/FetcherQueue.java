/*
 * Copyright (c) 1997-2009 101tec Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy 
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights 
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell 
 * copies of the Software, and to permit persons to whom the Software is 
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in 
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR 
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, 
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE 
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER 
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 */
package bixo.fetcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.log4j.Logger;

import cascading.tuple.TupleEntryCollector;

import bixo.cascading.BixoFlowProcess;
import bixo.config.FetcherPolicy;
import bixo.datum.FetchStatusCode;
import bixo.datum.FetchedDatum;
import bixo.datum.ScoredUrlDatum;

public class FetcherQueue implements IFetchListProvider {
    private static Logger LOGGER = Logger.getLogger(FetcherQueue.class);
    
    private String _domain;
    private List<ScoredUrlDatum> _queue;
    private FetcherPolicy _policy;
    private BixoFlowProcess _process;
    private TupleEntryCollector _collector;
    private int _numActiveFetchers;
    private long _nextFetchTime;
    private boolean _sorted;

    public FetcherQueue(String domain, FetcherPolicy policy, BixoFlowProcess process, TupleEntryCollector collector) {
        _domain = domain;
        _policy = policy;
        _process = process;
        _collector = collector;

        _numActiveFetchers = 0;
        _nextFetchTime = System.currentTimeMillis();
        _sorted = true;
        _queue = new ArrayList<ScoredUrlDatum>();

        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace(String.format("Setting up queue for %s with next fetch time of %d", _domain, _nextFetchTime));
        }
    }


    /**
     * Using queue terminology, offer up <fetchItem> as something to be queued.
     * 
     * @param ScoredUrlDatum - item that we'd like to have fetched. Must be valid format.
     * @return - true if we queued the URL
     */
    public synchronized boolean offer(ScoredUrlDatum ScoredUrlDatum) {
        // TODO KKr - add lock that prevents anyone from adding new items after we've
        // started polling.
        
        if (_queue.size() < _policy.getMaxUrls()) {
            _queue.add(ScoredUrlDatum);
            _sorted = false;
            return true;
        }

        // Since we have to insert, make sure the list is ordered first.
        sort();

        // TODO KKr - should we trim the queue? Given current time, we might have
        // more than getMaxUrls in the queue already.
        if (ScoredUrlDatum.getScore() <= _queue.get(_queue.size() - 1).getScore()) {
            return false;
        } else {
            // Get rid of last (lowest score) item in queue, then insert
            // new item at the right location.
            _queue.remove(_queue.size() - 1);
            
            int index = Collections.binarySearch(_queue, ScoredUrlDatum);
            if (index < 0) {
                index = -(index + 1);
            }
            
            _queue.add(index, ScoredUrlDatum);
            return true;
        }
    }

    /**
     * Tell the caller whether this queue is done (empty and all using threads done)
     * 
     * @return - true if it can be disposed of safely.
     */
    public synchronized boolean isEmpty() {
        return (_numActiveFetchers == 0) && (_queue.size() == 0);
    }

    /* (non-Javadoc)
     * @see bixo.fetcher.IFetchItemProvider#poll()
     */
    public synchronized FetchList poll() {
        // Based on our fetch policy, decide if we can return back one ore more URLs to
        // be fetched.
        FetchList result = null;
        
        if (_queue.size() == 0) {
            // Nothing to return
        } else if ((_policy.getCrawlEndTime() != FetcherPolicy.NO_CRAWL_END_TIME) && (System.currentTimeMillis() >= _policy.getCrawlEndTime())) {
            // We're past the end of the target fetch window, so bail.
            abortAll();
        } else if ((_numActiveFetchers == 0) && (System.currentTimeMillis() >= _nextFetchTime)) {
            _numActiveFetchers += 1;
            
            // Make sure we return things in sorted order
            sort();

            FetchRequest fetchRequest = _policy.getFetchRequest(_queue.size());
            int numUrls = fetchRequest.getNumUrls();
            result = new FetchList(this, _process, _collector);
            result.addAll(_queue.subList(0, numUrls));
            _queue.subList(0, numUrls).clear();
            
            _nextFetchTime = fetchRequest.getNextRequestTime();
            
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace(String.format("Return list for %s with next fetch time of %d", _domain, _nextFetchTime));
            }
        }
        
        if (result != null) {
            _process.increment(FetcherCounters.DOMAINS_FETCHING, 1);
        }
        
        return result;
    } // poll

    
    public int size() {
        return _queue.size();
    }
    
    
    /**
     * We're done trying to fetch <items>
     * @param items - items previously returned from call to poll()
     */
    public synchronized void release(FetchList items) {
        _process.decrement(FetcherCounters.DOMAINS_FETCHING, 1);
        _numActiveFetchers -= 1;
    }



    private void sort() {
        if (!_sorted) {
            _sorted = true;
            Collections.sort(_queue);
        }
    }
    
    public String getDomain() {
        return _domain;
    }


    /**
     * Write all entries out as being aborted.
     */
    public synchronized void abortAll() {
        for (ScoredUrlDatum datum : _queue) {
            synchronized (_collector) {
                String url = datum.getNormalizedUrl();
                FetchedDatum result = new FetchedDatum(FetchStatusCode.ABORTED, FetchedDatum.SC_UNKNOWN, url, url, 0, null, null, null, 0, datum.getMetaDataMap());
                _collector.add(result.toTuple());
            }
        }
        
        _queue.clear();
    }
    
}
