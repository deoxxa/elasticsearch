/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.search.aggregations.bucket.multi.range;

import com.carrotsearch.hppc.IntArrayList;
import org.apache.lucene.util.InPlaceMergeSorter;
import org.elasticsearch.index.fielddata.DoubleValues;
import org.elasticsearch.search.aggregations.Aggregator;
import org.elasticsearch.search.aggregations.InternalAggregation;
import org.elasticsearch.search.aggregations.InternalAggregations;
import org.elasticsearch.search.aggregations.context.AggregationContext;
import org.elasticsearch.search.aggregations.context.ValuesSourceConfig;
import org.elasticsearch.search.aggregations.context.numeric.NumericValuesSource;
import org.elasticsearch.search.aggregations.context.numeric.ValueFormatter;
import org.elasticsearch.search.aggregations.context.numeric.ValueParser;
import org.elasticsearch.search.aggregations.factory.AggregatorFactories;
import org.elasticsearch.search.aggregations.factory.ValueSourceAggregatorFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 *
 */
public class RangeAggregator extends Aggregator {

    public static class Range {

        public String key;
        public double from = Double.NEGATIVE_INFINITY;
        String fromAsStr;
        public double to = Double.POSITIVE_INFINITY;
        String toAsStr;

        public Range(String key, double from, String fromAsStr, double to, String toAsStr) {
            this.key = key;
            this.from = from;
            this.fromAsStr = fromAsStr;
            this.to = to;
            this.toAsStr = toAsStr;
        }

        boolean matches(double value) {
            return value >= from && value < to;
        }

        @Override
        public String toString() {
            return "(" + from + " to " + to + "]";
        }

        public void process(ValueParser parser, AggregationContext aggregationContext) {
            if (fromAsStr != null) {
                from = parser != null ? parser.parseDouble(fromAsStr, aggregationContext.searchContext()) : Double.valueOf(fromAsStr);
            }
            if (toAsStr != null) {
                to = parser != null ? parser.parseDouble(toAsStr, aggregationContext.searchContext()) : Double.valueOf(toAsStr);
            }
        }
    }

    private final NumericValuesSource valuesSource;
    private final Range[] ranges;
    private final boolean keyed;
    private final AbstractRangeBase.Factory rangeFactory;
    private final Collector collector;
    private final Aggregator[] subAggregators;
    private final long[] counts;

    public RangeAggregator(String name,
                           AggregatorFactories factories,
                           NumericValuesSource valuesSource,
                           AbstractRangeBase.Factory rangeFactory,
                           List<Range> rangesList,
                           boolean keyed,
                           AggregationContext aggregationContext,
                           Aggregator parent) {

        super(name, BucketAggregationMode.PER_BUCKET, factories, rangesList.size(), aggregationContext, parent);
        this.valuesSource = valuesSource;
        this.keyed = keyed;
        this.rangeFactory = rangeFactory;
        this.ranges = rangesList.toArray(new Range[rangesList.size()]);
        for (int i = 0; i < ranges.length; ++i) {
            final Range range = ranges[i];
            ValueParser parser = valuesSource != null ? valuesSource.parser() : null;
            range.process(parser, aggregationContext);
        }
        sortRanges();
        collector = valuesSource == null ? null : new Collector();
        subAggregators = factories.createBucketAggregatorsAsMulti(this, this.ranges.length);
        counts = new long[this.ranges.length];
    }

    private void sortRanges() {
        new InPlaceMergeSorter() {

            @Override
            protected void swap(int i, int j) {
                final Range tmp = ranges[i];
                ranges[i] = ranges[j];
                ranges[j] = tmp;
            }

            @Override
            protected int compare(int i, int j) {
                int cmp = Double.compare(ranges[i].from, ranges[j].from);
                if (cmp == 0) {
                    cmp = Double.compare(ranges[i].to, ranges[j].to);
                }
                return cmp;
            }
        };
    }

    @Override
    public boolean shouldCollect() {
        return collector != null;
    }

    @Override
    public void collect(int doc, long owningBucketOrdinal) throws IOException {
        assert owningBucketOrdinal == 0;
        collector.collect(doc);
    }

    @Override
    protected void doPostCollection() {
        collector.postCollection();
    }

    @Override
    public InternalAggregation buildAggregation(long owningBucketOrdinal) {
        assert owningBucketOrdinal == 0;
        // value source can be null in the case of unmapped fields
        final ValueFormatter formatter = valuesSource != null ? valuesSource.formatter() : null;

        final RangeBase.Bucket[] buckets = new RangeBase.Bucket[ranges.length];
        for (int i = 0; i < ranges.length; ++i) {
            final InternalAggregation[] aggregations = new InternalAggregation[subAggregators.length];
            for (int j = 0; j < subAggregators.length; ++j) {
                aggregations[j] = subAggregators[j].buildAggregation(i);
            }
            final InternalAggregations aggregation = new InternalAggregations(Arrays.asList(aggregations));
            final Range range = ranges[i];
            buckets[i] = rangeFactory.createBucket(range.key, range.from, range.to, counts[i], aggregation, formatter);
        }
        return rangeFactory.create(name, Arrays.asList(buckets), formatter, keyed);
    }

    class Collector {

        final boolean[] matched;
        final double[] maxTo;
        final IntArrayList matchedList;

        Collector() {
            matched = new boolean[ranges.length];
            maxTo = new double[ranges.length];
            maxTo[0] = ranges[0].to;
            for (int i = 1; i < ranges.length; ++i) {
                maxTo[i] = Math.max(ranges[i].to,maxTo[i-1]);
            }
            matchedList = new IntArrayList();
        }

        public void collect(int doc) throws IOException {
            final DoubleValues values = valuesSource.doubleValues();
            final int valuesCount = values.setDocument(doc);
            assert noMatchYet();
            for (int i = 0; i < valuesCount; ++i) {
                final double value = values.nextValue();
                collect(doc, value);
            }
            resetMatches();
        }

        private boolean noMatchYet() {
            for (int i = 0; i < ranges.length; ++i) {
                if (matched[i]) {
                    return false;
                }
            }
            return true;
        }

        private void resetMatches() {
            for (int i = 0; i < matchedList.size(); ++i) {
                matched[matchedList.get(i)] = false;
            }
            matchedList.clear();
        }

        private void collect(int doc, double value) throws IOException {
            int lo = 0, hi = ranges.length - 1; // all candidates are between these indexes
            int mid = (lo + hi) >>> 1;
            while (lo <= hi) {
                if (value < ranges[mid].from) {
                    hi = mid - 1;
                } else if (value >= maxTo[mid]) {
                    lo = mid + 1;
                } else {
                    break;
                }
                mid = (lo + hi) >>> 1;
            }

            // binary search the lower bound
            int startLo = lo, startHi = mid;
            while (startLo <= startHi) {
                final int startMid = (startLo + startHi) >>> 1;
                if (value >= maxTo[startMid]) {
                    startLo = startMid + 1;
                } else {
                    startHi = startMid - 1;
                }
            }

            // binary search the upper bound
            int endLo = mid, endHi = hi;
            while (endLo <= endHi) {
                final int endMid = (endLo + endHi) >>> 1;
                if (value < ranges[endMid].from) {
                    endHi = endMid - 1;
                } else {
                    endLo = endMid + 1;
                }
            }

            assert startLo == 0 || value >= maxTo[startLo - 1];
            assert endHi == ranges.length - 1 || value < ranges[endHi + 1].from;

            for (int i = startLo; i <= endHi; ++i) {
                if (!matched[i] && ranges[i].matches(value)) {
                    matched[i] = true;
                    matchedList.add(i);
                    ++counts[i];
                    for (Aggregator aggregator : subAggregators) {
                        aggregator.collect(doc, i);
                    }
                }
            }
        }

        public void postCollection() {
            for (int i = 0; i < subAggregators.length; i++) {
                subAggregators[i].postCollection();
            }
        }
    }

    static class BucketCollector extends org.elasticsearch.search.aggregations.bucket.BucketCollector {

        private final Range range;

        BucketCollector(long ord, Range range, Aggregator[] aggregators) {
            super(ord, aggregators);
            this.range = range;
        }

        @Override
        protected boolean onDoc(int doc) throws IOException {
            return true;
        }
    }

    public static class Unmapped extends Aggregator {
        private final List<RangeAggregator.Range> ranges;
        private final boolean keyed;
        private final AbstractRangeBase.Factory factory;
        private final ValueFormatter formatter;
        private final ValueParser parser;

        public Unmapped(String name,
                        List<RangeAggregator.Range> ranges,
                        boolean keyed,
                        ValueFormatter formatter,
                        ValueParser parser,
                        AggregationContext aggregationContext,
                        Aggregator parent,
                        AbstractRangeBase.Factory factory) {

            super(name, BucketAggregationMode.PER_BUCKET, AggregatorFactories.EMPTY, 0, aggregationContext, parent);
            this.ranges = ranges;
            this.keyed = keyed;
            this.formatter = formatter;
            this.parser = parser;
            this.factory = factory;
        }

        @Override
        public boolean shouldCollect() {
            return false;
        }

        @Override
        public void collect(int doc, long owningBucketOrdinal) throws IOException {
        }

        @Override
        protected void doPostCollection() {
        }


        @Override
        public AbstractRangeBase buildAggregation(long owningBucketOrdinal) {
            assert owningBucketOrdinal == 0;
            List<RangeBase.Bucket> buckets = new ArrayList<RangeBase.Bucket>(ranges.size());
            for (RangeAggregator.Range range : ranges) {
                range.process(parser, context) ;
                buckets.add(factory.createBucket(range.key, range.from, range.to, 0, InternalAggregations.EMPTY, formatter));
            }
            return factory.create(name, buckets, formatter, keyed);
        }
    }

    public static class Factory extends ValueSourceAggregatorFactory<NumericValuesSource> {

        private final AbstractRangeBase.Factory rangeFactory;
        private final List<Range> ranges;
        private final boolean keyed;

        public Factory(String name, ValuesSourceConfig<NumericValuesSource> valueSourceConfig, AbstractRangeBase.Factory rangeFactory, List<Range> ranges, boolean keyed) {
            super(name, rangeFactory.type(), valueSourceConfig);
            this.rangeFactory = rangeFactory;
            this.ranges = ranges;
            this.keyed = keyed;
        }

        @Override
        public BucketAggregationMode bucketMode() {
            return BucketAggregationMode.PER_BUCKET;
        }

        @Override
        protected Aggregator createUnmapped(AggregationContext aggregationContext, Aggregator parent) {
            return new Unmapped(name, ranges, keyed, valuesSourceConfig.formatter(), valuesSourceConfig.parser(), aggregationContext, parent, rangeFactory);
        }

        @Override
        protected Aggregator create(NumericValuesSource valuesSource, long expectedBucketsCount, AggregationContext aggregationContext, Aggregator parent) {
            return new RangeAggregator(name, factories, valuesSource, rangeFactory, ranges, keyed, aggregationContext, parent);
        }
    }

}
