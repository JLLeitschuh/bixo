/*
 * Copyright (c) 2007-2009 Concurrent, Inc. All Rights Reserved.
 *
 * Project and contact information: http://www.cascading.org/
 *
 * This file is part of the Cascading project.
 *
 * Cascading is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Cascading is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Cascading.  If not, see <http://www.gnu.org/licenses/>.
 */

package bixo.cascading;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.log4j.Logger;

import cascading.scheme.Scheme;
import cascading.scheme.SequenceFile;
import cascading.tap.SinkTap;
import cascading.tap.Tap;
import cascading.tuple.Fields;
import cascading.tuple.Tuple;
import cascading.tuple.TupleEntry;
import cascading.tuple.TupleEntryCollector;

/**
 *Contribution by Chris Wensel
 */

public class MultiSinkTap extends SinkTap {
    /** Field LOG */
    private static final Logger LOG = Logger.getLogger(MultiSinkTap.class);

    private Tap[] _taps;

    public MultiSinkTap(Tap... taps) {
        _taps = taps;
    }

    public Tap[] getTaps() {
        return _taps;
    }

    @Override
    public boolean isWriteDirect() {
        return true;
    }

    @Override
    public Path getPath() {
        return new Path("__temporary_multisink" + Integer.toString((int) (10000000 * Math.random())));
    }

    @Override
    public TupleEntryCollector openForWrite(JobConf conf) throws IOException {
        return new MultiSinkCollector(conf, getTaps());
    }

    @Override
    public void sinkInit(JobConf conf) throws IOException {
        for (Tap tap : getTaps())
            tap.sinkInit(new JobConf(conf));
    }

    @Override
    public boolean makeDirs(JobConf conf) throws IOException {
        for (Tap tap : getTaps()) {
            if (!tap.makeDirs(conf))
                return false;
        }

        return true;
    }

    @Override
    public boolean deletePath(JobConf conf) throws IOException {
        for (Tap tap : getTaps()) {
            if (!tap.deletePath(conf))
                return false;
        }

        return true;
    }

    @Override
    public boolean pathExists(JobConf conf) throws IOException {
        for (Tap tap : getTaps()) {
            if (!tap.pathExists(conf))
                return false;
        }

        return true;
    }

    @Override
    public long getPathModified(JobConf conf) throws IOException {
        long modified = getTaps()[0].getPathModified(conf);

        for (int i = 1; i < getTaps().length; i++)
            modified = Math.max(getTaps()[i].getPathModified(conf), modified);

        return modified;
    }

    @Override
    public void sink(TupleEntry tupleEntry, OutputCollector outputCollector) throws IOException {
        // for( Tap tap : getTaps() )
        // tap.sink( tupleEntry, outputCollector );

        outputCollector.collect(null, tupleEntry);

    }

    @Override
    public Scheme getScheme() {
        if (super.getScheme() != null)
            return super.getScheme();

        Set<Comparable> fieldNames = new LinkedHashSet<Comparable>();

        for (int i = 0; i < getTaps().length; i++) {
            for (Object o : getTaps()[i].getSinkFields())
                fieldNames.add((Comparable) o);
        }

        Fields allFields = new Fields(fieldNames.toArray(new Comparable[fieldNames.size()]));

        setScheme(new SequenceFile(allFields));

        return super.getScheme();
    }

    @Override
    public String toString() {
        return "MultiSinkTap[" + (_taps == null ? null : Arrays.asList(_taps)) + ']';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof MultiSinkTap))
            return false;
        if (!super.equals(o))
            return false;

        MultiSinkTap that = (MultiSinkTap) o;

        if (!Arrays.equals(_taps, that._taps))
            return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + (_taps != null ? Arrays.hashCode(_taps) : 0);
        return result;

    }

    private class MultiSinkCollector extends TupleEntryCollector implements OutputCollector {
        JobConf _conf;
        OutputCollector[] _collectors;
        Tap[] _taps;

        public MultiSinkCollector(JobConf conf, Tap... taps) throws IOException {
            _conf = conf;
            _taps = taps;
            _collectors = new OutputCollector[taps.length];
            for (int j = 0; j < taps.length; j++) {
                LOG.info("opening for write: " + taps[j].toString());
                _collectors[j] = ((OutputCollector) taps[j].openForWrite(conf));
            }
        }

        protected void collect(Tuple tuple) {
            throw new UnsupportedOperationException("collect should never be called on TemplateCollector");
        }

        @Override
        public void close() {
            super.close();

            try {
                for (OutputCollector collector : _collectors) {
                    try {
                        ((TupleEntryCollector) collector).close();
                    } catch (Exception exception) {
                        // do nothing
                    }
                }
            } finally {
                _collectors = new OutputCollector[0];
            }
        }

        public void collect(Object key, Object value) throws IOException {
            for (int i = 0; i < _taps.length; i++) {
                _taps[i].getScheme().sink((TupleEntry) value, _collectors[i]);

            }
        }
    }
}
