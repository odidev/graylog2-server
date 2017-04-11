/**
 * This file is part of Graylog.
 *
 * Graylog is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Graylog is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Graylog.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.graylog2.indexer.searches;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.google.common.collect.ImmutableSortedSet;
import com.lordofthejars.nosqlunit.annotation.UsingDataSet;
import com.lordofthejars.nosqlunit.core.LoadStrategyEnum;
import org.elasticsearch.index.query.RangeQueryBuilder;
import org.graylog2.AbstractESTest;
import org.graylog2.Configuration;
import org.graylog2.indexer.IndexHelper;
import org.graylog2.indexer.IndexSet;
import org.graylog2.indexer.TestIndexSet;
import org.graylog2.indexer.indexset.IndexSetConfig;
import org.graylog2.indexer.indices.Indices;
import org.graylog2.indexer.nosqlunit.IndexCreatingLoadStrategyFactory;
import org.graylog2.indexer.ranges.IndexRange;
import org.graylog2.indexer.ranges.IndexRangeComparator;
import org.graylog2.indexer.ranges.IndexRangeService;
import org.graylog2.indexer.ranges.MongoIndexRange;
import org.graylog2.indexer.results.CountResult;
import org.graylog2.indexer.results.FieldStatsResult;
import org.graylog2.indexer.results.HistogramResult;
import org.graylog2.indexer.results.TermsResult;
import org.graylog2.indexer.results.TermsStatsResult;
import org.graylog2.indexer.retention.strategies.DeletionRetentionStrategy;
import org.graylog2.indexer.retention.strategies.DeletionRetentionStrategyConfig;
import org.graylog2.indexer.rotation.strategies.MessageCountRotationStrategy;
import org.graylog2.indexer.rotation.strategies.MessageCountRotationStrategyConfig;
import org.graylog2.plugin.Tools;
import org.graylog2.plugin.indexer.searches.timeranges.AbsoluteRange;
import org.graylog2.plugin.indexer.searches.timeranges.KeywordRange;
import org.graylog2.plugin.indexer.searches.timeranges.RelativeRange;
import org.graylog2.plugin.indexer.searches.timeranges.TimeRange;
import org.graylog2.plugin.streams.Stream;
import org.graylog2.streams.StreamService;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;

import static com.lordofthejars.nosqlunit.elasticsearch2.ElasticsearchRule.ElasticsearchRuleBuilder.newElasticsearchRule;
import static org.assertj.core.api.Assertions.assertThat;
import static org.joda.time.DateTimeZone.UTC;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SearchesTest extends AbstractESTest {
    private static final String REQUEST_TIMER_NAME = "org.graylog2.indexer.searches.Searches.elasticsearch.requests";
    private static final String RANGES_HISTOGRAM_NAME = "org.graylog2.indexer.searches.Searches.elasticsearch.ranges";

    @Rule
    public final MockitoRule mockitoRule = MockitoJUnit.rule();

    private static final String INDEX_NAME = "graylog_0";
    private static final SortedSet<IndexRange> INDEX_RANGES = ImmutableSortedSet
            .orderedBy(new IndexRangeComparator())
            .add(new IndexRange() {
                @Override
                public String indexName() {
                    return INDEX_NAME;
                }

                @Override
                public DateTime calculatedAt() {
                    return DateTime.now(UTC);
                }

                @Override
                public DateTime end() {
                    return new DateTime(2015, 1, 1, 0, 0, UTC);
                }

                @Override
                public int calculationDuration() {
                    return 0;
                }

                @Override
                public List<String> streamIds() {
                    return null;
                }

                @Override
                public DateTime begin() {
                    return new DateTime(0L, UTC);
                }
            }).build();

    private final IndexSetConfig indexSetConfig;
    private final IndexSet indexSet;

    @Mock
    private IndexRangeService indexRangeService;

    @Mock
    private StreamService streamService;

    private MetricRegistry metricRegistry;
    private Searches searches;

    public SearchesTest() {
        this.indexSetConfig = IndexSetConfig.builder()
                .id("index-set-1")
                .title("Index set 1")
                .description("For testing")
                .indexPrefix("graylog")
                .creationDate(ZonedDateTime.now())
                .shards(1)
                .replicas(0)
                .rotationStrategyClass(MessageCountRotationStrategy.class.getCanonicalName())
                .rotationStrategy(MessageCountRotationStrategyConfig.createDefault())
                .retentionStrategyClass(DeletionRetentionStrategy.class.getCanonicalName())
                .retentionStrategy(DeletionRetentionStrategyConfig.createDefault())
                .indexAnalyzer("standard")
                .indexTemplateName("template-1")
                .indexOptimizationMaxNumSegments(1)
                .indexOptimizationDisabled(false)
                .build();
        this.indexSet = new TestIndexSet(indexSetConfig);
        this.elasticsearchRule.setLoadStrategyFactory(new IndexCreatingLoadStrategyFactory(indexSet, Collections.singleton(INDEX_NAME)));
    }

    @Before
    public void setUp() throws Exception {
        when(indexRangeService.find(any(DateTime.class), any(DateTime.class))).thenReturn(INDEX_RANGES);
        metricRegistry = new MetricRegistry();
        searches = new Searches(new Configuration(), indexRangeService, client, metricRegistry, streamService, mock(Indices.class));
    }

    @Test
    @UsingDataSet(loadStrategy = LoadStrategyEnum.CLEAN_INSERT)
    public void testCount() throws Exception {
        CountResult result = searches.count("*", AbsoluteRange.create(
                new DateTime(2015, 1, 1, 0, 0, DateTimeZone.UTC),
                new DateTime(2015, 1, 2, 0, 0, DateTimeZone.UTC)));

        assertThat(result.count()).isEqualTo(10L);
    }

    @Test
    @UsingDataSet(loadStrategy = LoadStrategyEnum.CLEAN_INSERT)
    public void countRecordsMetrics() throws Exception {
        CountResult result = searches.count("*", AbsoluteRange.create(
                new DateTime(2015, 1, 1, 0, 0, DateTimeZone.UTC),
                new DateTime(2015, 1, 2, 0, 0, DateTimeZone.UTC)));

        assertThat(metricRegistry.getTimers()).containsKey(REQUEST_TIMER_NAME);
        assertThat(metricRegistry.getHistograms()).containsKey(RANGES_HISTOGRAM_NAME);

        Timer timer = metricRegistry.timer(REQUEST_TIMER_NAME);
        assertThat(timer.getCount()).isEqualTo(1L);
    }

    @Test
    @UsingDataSet(loadStrategy = LoadStrategyEnum.CLEAN_INSERT)
    public void testTerms() throws Exception {
        TermsResult result = searches.terms("n", 25, "*", AbsoluteRange.create(
                new DateTime(2015, 1, 1, 0, 0, DateTimeZone.UTC),
                new DateTime(2015, 1, 2, 0, 0, DateTimeZone.UTC)));

        assertThat(result.getTotal()).isEqualTo(10L);
        assertThat(result.getMissing()).isEqualTo(2L);
        assertThat(result.getTerms())
                .hasSize(4)
                .containsEntry("1", 2L)
                .containsEntry("2", 2L)
                .containsEntry("3", 3L)
                .containsEntry("4", 1L);
    }

    @Test
    @UsingDataSet(loadStrategy = LoadStrategyEnum.CLEAN_INSERT)
    public void testTermsWithNonExistingIndex() throws Exception {
        final SortedSet<IndexRange> indexRanges = ImmutableSortedSet
                .orderedBy(IndexRange.COMPARATOR)
                .add(MongoIndexRange.create(INDEX_NAME,
                        new DateTime(0L, UTC),
                        new DateTime(2015, 1, 1, 0, 0, UTC),
                        DateTime.now(UTC),
                        0,
                        null))
                .add(MongoIndexRange.create("does-not-exist",
                        new DateTime(0L, UTC),
                        new DateTime(2015, 1, 1, 0, 0, UTC),
                        DateTime.now(UTC),
                        0,
                        null))
                .build();
        when(indexRangeService.find(any(DateTime.class), any(DateTime.class))).thenReturn(indexRanges);

        TermsResult result = searches.terms("n", 25, "*", AbsoluteRange.create(
                new DateTime(2015, 1, 1, 0, 0, DateTimeZone.UTC),
                new DateTime(2015, 1, 2, 0, 0, DateTimeZone.UTC)));

        assertThat(result.getTotal()).isEqualTo(10L);
        assertThat(result.getMissing()).isEqualTo(2L);
        assertThat(result.getTerms())
                .hasSize(4)
                .containsEntry("1", 2L)
                .containsEntry("2", 2L)
                .containsEntry("3", 3L)
                .containsEntry("4", 1L);
    }

    @Test
    @UsingDataSet(loadStrategy = LoadStrategyEnum.CLEAN_INSERT)
    public void termsRecordsMetrics() throws Exception {
        TermsResult result = searches.terms("n", 25, "*", AbsoluteRange.create(
                new DateTime(2015, 1, 1, 0, 0, DateTimeZone.UTC),
                new DateTime(2015, 1, 2, 0, 0, DateTimeZone.UTC)));

        assertThat(metricRegistry.getTimers()).containsKey(REQUEST_TIMER_NAME);
        assertThat(metricRegistry.getHistograms()).containsKey(RANGES_HISTOGRAM_NAME);

        Timer timer = metricRegistry.timer(REQUEST_TIMER_NAME);
        assertThat(timer.getCount()).isEqualTo(1L);

        Histogram histogram = metricRegistry.histogram(RANGES_HISTOGRAM_NAME);
        assertThat(histogram.getCount()).isEqualTo(1L);
        assertThat(histogram.getSnapshot().getValues()).containsExactly(86400L);
    }

    @Test
    @UsingDataSet(loadStrategy = LoadStrategyEnum.CLEAN_INSERT)
    public void testTermsAscending() throws Exception {
        TermsResult result = searches.terms("n", 1, "*", null, AbsoluteRange.create(
            new DateTime(2015, 1, 1, 0, 0, DateTimeZone.UTC),
            new DateTime(2015, 1, 2, 0, 0, DateTimeZone.UTC)), Sorting.Direction.ASC);

        assertThat(result.getTotal()).isEqualTo(10L);
        assertThat(result.getMissing()).isEqualTo(2L);
        assertThat(result.getTerms())
            .hasSize(1)
            .containsEntry("4", 1L);
    }

    @Test
    @UsingDataSet(loadStrategy = LoadStrategyEnum.CLEAN_INSERT)
    public void testTermsStats() throws Exception {
        TermsStatsResult r = searches.termsStats("message", "n", Searches.TermsStatsOrder.COUNT, 25, "*",
                AbsoluteRange.create(
                        new DateTime(2015, 1, 1, 0, 0, DateTimeZone.UTC),
                        new DateTime(2015, 1, 2, 0, 0, DateTimeZone.UTC))
        );

        assertThat(r.getResults()).hasSize(2);
        assertThat(r.getResults().get(0))
                .hasSize(7)
                .containsEntry("key_field", "ho");
    }

    @Test
    @UsingDataSet(loadStrategy = LoadStrategyEnum.CLEAN_INSERT)
    public void termsStatsRecordsMetrics() throws Exception {
        TermsStatsResult r = searches.termsStats("message", "n", Searches.TermsStatsOrder.COUNT, 25, "*",
                AbsoluteRange.create(
                        new DateTime(2015, 1, 1, 0, 0, DateTimeZone.UTC),
                        new DateTime(2015, 1, 2, 0, 0, DateTimeZone.UTC))
        );

        assertThat(metricRegistry.getTimers()).containsKey(REQUEST_TIMER_NAME);
        assertThat(metricRegistry.getHistograms()).containsKey(RANGES_HISTOGRAM_NAME);

        Timer timer = metricRegistry.timer(REQUEST_TIMER_NAME);
        assertThat(timer.getCount()).isEqualTo(1L);

        Histogram histogram = metricRegistry.histogram(RANGES_HISTOGRAM_NAME);
        assertThat(histogram.getCount()).isEqualTo(1L);
        assertThat(histogram.getSnapshot().getValues()).containsExactly(86400L);
    }

    @Test
    @UsingDataSet(loadStrategy = LoadStrategyEnum.CLEAN_INSERT)
    public void testFieldStats() throws Exception {
        FieldStatsResult result = searches.fieldStats("n", "*", AbsoluteRange.create(
                new DateTime(2015, 1, 1, 0, 0, DateTimeZone.UTC),
                new DateTime(2015, 1, 2, 0, 0, DateTimeZone.UTC)));

        assertThat(result.getSearchHits()).hasSize(10);
        assertThat(result.getCount()).isEqualTo(8);
        assertThat(result.getMin()).isEqualTo(1.0);
        assertThat(result.getMax()).isEqualTo(4.0);
        assertThat(result.getMean()).isEqualTo(2.375);
        assertThat(result.getSum()).isEqualTo(19.0);
        assertThat(result.getSumOfSquares()).isEqualTo(53.0);
        assertThat(result.getVariance()).isEqualTo(0.984375);
        assertThat(result.getStdDeviation()).isEqualTo(0.9921567416492215);
    }

    @Test
    @UsingDataSet(loadStrategy = LoadStrategyEnum.CLEAN_INSERT)
    public void fieldStatsRecordsMetrics() throws Exception {
        FieldStatsResult result = searches.fieldStats("n", "*", AbsoluteRange.create(
                new DateTime(2015, 1, 1, 0, 0, DateTimeZone.UTC),
                new DateTime(2015, 1, 2, 0, 0, DateTimeZone.UTC)));

        assertThat(metricRegistry.getTimers()).containsKey(REQUEST_TIMER_NAME);
        assertThat(metricRegistry.getHistograms()).containsKey(RANGES_HISTOGRAM_NAME);

        Timer timer = metricRegistry.timer(REQUEST_TIMER_NAME);
        assertThat(timer.getCount()).isEqualTo(1L);

        Histogram histogram = metricRegistry.histogram(RANGES_HISTOGRAM_NAME);
        assertThat(histogram.getCount()).isEqualTo(1L);
        assertThat(histogram.getSnapshot().getValues()).containsExactly(86400L);
    }

    @Test
    @UsingDataSet(loadStrategy = LoadStrategyEnum.CLEAN_INSERT)
    @SuppressWarnings("unchecked")
    public void testHistogram() throws Exception {
        final AbsoluteRange range = AbsoluteRange.create(new DateTime(2015, 1, 1, 0, 0, DateTimeZone.UTC).withZone(UTC), new DateTime(2015, 1, 2, 0, 0, DateTimeZone.UTC).withZone(UTC));
        HistogramResult h = searches.histogram("*", Searches.DateHistogramInterval.HOUR, range);

        assertThat(h.getInterval()).isEqualTo(Searches.DateHistogramInterval.HOUR);
        assertThat(h.getHistogramBoundaries()).isEqualTo(range);
        assertThat(h.getResults())
                .hasSize(5)
                .containsEntry(new DateTime(2015, 1, 1, 1, 0, UTC).getMillis() / 1000L, 2L)
                .containsEntry(new DateTime(2015, 1, 1, 2, 0, UTC).getMillis() / 1000L, 2L)
                .containsEntry(new DateTime(2015, 1, 1, 3, 0, UTC).getMillis() / 1000L, 2L)
                .containsEntry(new DateTime(2015, 1, 1, 4, 0, UTC).getMillis() / 1000L, 2L)
                .containsEntry(new DateTime(2015, 1, 1, 5, 0, UTC).getMillis() / 1000L, 2L);
    }

    @Test
    @UsingDataSet(loadStrategy = LoadStrategyEnum.CLEAN_INSERT)
    @SuppressWarnings("unchecked")
    public void testHistogramWithNonExistingIndex() throws Exception {
        final SortedSet<IndexRange> indexRanges = ImmutableSortedSet
                .orderedBy(IndexRange.COMPARATOR)
                .add(MongoIndexRange.create(INDEX_NAME,
                        new DateTime(0L, UTC),
                        new DateTime(2015, 1, 1, 0, 0, UTC),
                        DateTime.now(UTC),
                        0,
                        null))
                .add(MongoIndexRange.create("does-not-exist",
                        new DateTime(0L, UTC),
                        new DateTime(2015, 1, 1, 0, 0, UTC),
                        DateTime.now(UTC),
                        0,
                        null))
                .build();
        when(indexRangeService.find(any(DateTime.class), any(DateTime.class))).thenReturn(indexRanges);

        final AbsoluteRange range = AbsoluteRange.create(new DateTime(2015, 1, 1, 0, 0, DateTimeZone.UTC).withZone(UTC), new DateTime(2015, 1, 2, 0, 0, DateTimeZone.UTC).withZone(UTC));
        HistogramResult h = searches.histogram("*", Searches.DateHistogramInterval.HOUR, range);

        assertThat(h.getInterval()).isEqualTo(Searches.DateHistogramInterval.HOUR);
        assertThat(h.getHistogramBoundaries()).isEqualTo(range);
        assertThat(h.getResults())
                .hasSize(5)
                .containsEntry(new DateTime(2015, 1, 1, 1, 0, UTC).getMillis() / 1000L, 2L)
                .containsEntry(new DateTime(2015, 1, 1, 2, 0, UTC).getMillis() / 1000L, 2L)
                .containsEntry(new DateTime(2015, 1, 1, 3, 0, UTC).getMillis() / 1000L, 2L)
                .containsEntry(new DateTime(2015, 1, 1, 4, 0, UTC).getMillis() / 1000L, 2L)
                .containsEntry(new DateTime(2015, 1, 1, 5, 0, UTC).getMillis() / 1000L, 2L);
    }

    @Test
    @UsingDataSet(loadStrategy = LoadStrategyEnum.CLEAN_INSERT)
    public void histogramRecordsMetrics() throws Exception {
        final AbsoluteRange range = AbsoluteRange.create(new DateTime(2015, 1, 1, 0, 0, DateTimeZone.UTC), new DateTime(2015, 1, 2, 0, 0, DateTimeZone.UTC));
        HistogramResult h = searches.histogram("*", Searches.DateHistogramInterval.MINUTE, range);

        assertThat(metricRegistry.getTimers()).containsKey(REQUEST_TIMER_NAME);
        assertThat(metricRegistry.getHistograms()).containsKey(RANGES_HISTOGRAM_NAME);

        Timer timer = metricRegistry.timer(REQUEST_TIMER_NAME);
        assertThat(timer.getCount()).isEqualTo(1L);

        Histogram histogram = metricRegistry.histogram(RANGES_HISTOGRAM_NAME);
        assertThat(histogram.getCount()).isEqualTo(1L);
        assertThat(histogram.getSnapshot().getValues()).containsExactly(86400L);
    }

    @Test
    @UsingDataSet(loadStrategy = LoadStrategyEnum.CLEAN_INSERT)
    @SuppressWarnings("unchecked")
    public void testFieldHistogram() throws Exception {
        final AbsoluteRange range = AbsoluteRange.create(new DateTime(2015, 1, 1, 0, 0, DateTimeZone.UTC).withZone(UTC), new DateTime(2015, 1, 2, 0, 0, DateTimeZone.UTC).withZone(UTC));
        HistogramResult h = searches.fieldHistogram("*", "n", Searches.DateHistogramInterval.HOUR, null, range, false);

        assertThat(h.getInterval()).isEqualTo(Searches.DateHistogramInterval.HOUR);
        assertThat(h.getHistogramBoundaries()).isEqualTo(range);
        assertThat(h.getResults()).hasSize(5);
        assertThat((Map<String, Number>) h.getResults().get(new DateTime(2015, 1, 1, 1, 0, UTC).getMillis() / 1000L))
                .containsEntry("total_count", 2L)
                .containsEntry("total", 0.0);
        assertThat((Map<String, Number>) h.getResults().get(new DateTime(2015, 1, 1, 2, 0, UTC).getMillis() / 1000L))
                .containsEntry("total_count", 2L)
                .containsEntry("total", 4.0)
                .containsEntry("mean", 2.0);
    }

    @Test
    @UsingDataSet(loadStrategy = LoadStrategyEnum.CLEAN_INSERT)
    public void fieldHistogramRecordsMetrics() throws Exception {
        final AbsoluteRange range = AbsoluteRange.create(new DateTime(2015, 1, 1, 0, 0, DateTimeZone.UTC), new DateTime(2015, 1, 2, 0, 0, DateTimeZone.UTC));
        HistogramResult h = searches.fieldHistogram("*", "n", Searches.DateHistogramInterval.MINUTE, null, range, false);

        assertThat(metricRegistry.getTimers()).containsKey(REQUEST_TIMER_NAME);
        assertThat(metricRegistry.getHistograms()).containsKey(RANGES_HISTOGRAM_NAME);

        Timer timer = metricRegistry.timer(REQUEST_TIMER_NAME);
        assertThat(timer.getCount()).isEqualTo(1L);

        Histogram histogram = metricRegistry.histogram(RANGES_HISTOGRAM_NAME);
        assertThat(histogram.getCount()).isEqualTo(1L);
        assertThat(histogram.getSnapshot().getValues()).containsExactly(86400L);
    }

    @Test
    public void determineAffectedIndicesWithRangesIncludesDeflectorTarget() throws Exception {
        final DateTime now = DateTime.now(DateTimeZone.UTC);
        final MongoIndexRange indexRange0 = MongoIndexRange.create("graylog_0", now, now.plusDays(1), now, 0);
        final MongoIndexRange indexRange1 = MongoIndexRange.create("graylog_1", now.plusDays(1), now.plusDays(2), now, 0);
        final MongoIndexRange indexRangeLatest = MongoIndexRange.create("graylog_2", new DateTime(0L, DateTimeZone.UTC), new DateTime(0L, DateTimeZone.UTC), now, 0);
        final SortedSet<IndexRange> indices = ImmutableSortedSet.orderedBy(IndexRange.COMPARATOR)
                .add(indexRange0)
                .add(indexRange1)
                .add(indexRangeLatest)
                .build();

        when(indexRangeService.find(any(DateTime.class), any(DateTime.class))).thenReturn(indices);

        final TimeRange absoluteRange = AbsoluteRange.create(now.minusDays(1), now.plusDays(1));
        final TimeRange keywordRange = KeywordRange.create("1 day ago");
        final TimeRange relativeRange = RelativeRange.create(3600);

        assertThat(searches.determineAffectedIndicesWithRanges(absoluteRange, null))
                .containsExactly(indexRangeLatest, indexRange0, indexRange1);
        assertThat(searches.determineAffectedIndicesWithRanges(keywordRange, null))
                .containsExactly(indexRangeLatest, indexRange0, indexRange1);
        assertThat(searches.determineAffectedIndicesWithRanges(relativeRange, null))
                .containsExactly(indexRangeLatest, indexRange0, indexRange1);
    }

    @Test
    public void determineAffectedIndicesWithRangesDoesNotIncludesDeflectorTargetIfMissing() throws Exception {
        final DateTime now = DateTime.now(DateTimeZone.UTC);
        final MongoIndexRange indexRange0 = MongoIndexRange.create("graylog_0", now, now.plusDays(1), now, 0);
        final MongoIndexRange indexRange1 = MongoIndexRange.create("graylog_1", now.plusDays(1), now.plusDays(2), now, 0);
        final SortedSet<IndexRange> indices = ImmutableSortedSet.orderedBy(IndexRange.COMPARATOR)
                .add(indexRange0)
                .add(indexRange1)
                .build();

        when(indexRangeService.find(any(DateTime.class), any(DateTime.class))).thenReturn(indices);

        final TimeRange absoluteRange = AbsoluteRange.create(now.minusDays(1), now.plusDays(1));
        final TimeRange keywordRange = KeywordRange.create("1 day ago");
        final TimeRange relativeRange = RelativeRange.create(3600);

        assertThat(searches.determineAffectedIndicesWithRanges(absoluteRange, null))
                .containsExactly(indexRange0, indexRange1);
        assertThat(searches.determineAffectedIndicesWithRanges(keywordRange, null))
                .containsExactly(indexRange0, indexRange1);
        assertThat(searches.determineAffectedIndicesWithRanges(relativeRange, null))
                .containsExactly(indexRange0, indexRange1);
    }

    @Test
    public void determineAffectedIndicesIncludesDeflectorTarget() throws Exception {
        final DateTime now = DateTime.now(DateTimeZone.UTC);
        final MongoIndexRange indexRange0 = MongoIndexRange.create("graylog_0", now, now.plusDays(1), now, 0);
        final MongoIndexRange indexRange1 = MongoIndexRange.create("graylog_1", now.plusDays(1), now.plusDays(2), now, 0);
        final MongoIndexRange indexRangeLatest = MongoIndexRange.create("graylog_2", new DateTime(0L, DateTimeZone.UTC), new DateTime(0L, DateTimeZone.UTC), now, 0);
        final SortedSet<IndexRange> indices = ImmutableSortedSet.orderedBy(IndexRange.COMPARATOR)
                .add(indexRange0)
                .add(indexRange1)
                .add(indexRangeLatest)
                .build();

        when(indexRangeService.find(any(DateTime.class), any(DateTime.class))).thenReturn(indices);

        final TimeRange absoluteRange = AbsoluteRange.create(now.minusDays(1), now.plusDays(1));
        final TimeRange keywordRange = KeywordRange.create("1 day ago");
        final TimeRange relativeRange = RelativeRange.create(3600);

        assertThat(searches.determineAffectedIndices(absoluteRange, null))
                .containsExactlyInAnyOrder(indexRangeLatest.indexName(), indexRange0.indexName(), indexRange1.indexName());
        assertThat(searches.determineAffectedIndices(keywordRange, null))
                .containsExactlyInAnyOrder(indexRangeLatest.indexName(), indexRange0.indexName(), indexRange1.indexName());
        assertThat(searches.determineAffectedIndices(relativeRange, null))
                .containsExactlyInAnyOrder(indexRangeLatest.indexName(), indexRange0.indexName(), indexRange1.indexName());
    }

    @Test
    public void determineAffectedIndicesDoesNotIncludesDeflectorTargetIfMissing() throws Exception {
        final DateTime now = DateTime.now(DateTimeZone.UTC);
        final MongoIndexRange indexRange0 = MongoIndexRange.create("graylog_0", now, now.plusDays(1), now, 0);
        final MongoIndexRange indexRange1 = MongoIndexRange.create("graylog_1", now.plusDays(1), now.plusDays(2), now, 0);
        final SortedSet<IndexRange> indices = ImmutableSortedSet.orderedBy(IndexRange.COMPARATOR)
                .add(indexRange0)
                .add(indexRange1)
                .build();

        when(indexRangeService.find(any(DateTime.class), any(DateTime.class))).thenReturn(indices);

        final TimeRange absoluteRange = AbsoluteRange.create(now.minusDays(1), now.plusDays(1));
        final TimeRange keywordRange = KeywordRange.create("1 day ago");
        final TimeRange relativeRange = RelativeRange.create(3600);

        assertThat(searches.determineAffectedIndices(absoluteRange, null))
                .containsOnly(indexRange0.indexName(), indexRange1.indexName());
        assertThat(searches.determineAffectedIndices(keywordRange, null))
                .containsOnly(indexRange0.indexName(), indexRange1.indexName());
        assertThat(searches.determineAffectedIndices(relativeRange, null))
                .containsOnly(indexRange0.indexName(), indexRange1.indexName());
    }

    @Test
    public void getTimestampRangeFilterReturnsNullIfTimeRangeIsNull() {
        assertThat(IndexHelper.getTimestampRangeFilter(null)).isNull();
    }

    @Test
    public void getTimestampRangeFilterReturnsRangeQueryWithGivenTimeRange() {
        final DateTime from = new DateTime(2016, 1, 15, 12, 0, DateTimeZone.UTC);
        final DateTime to = from.plusHours(1);
        final TimeRange timeRange = AbsoluteRange.create(from, to);
        final RangeQueryBuilder queryBuilder = (RangeQueryBuilder) IndexHelper.getTimestampRangeFilter(timeRange);
        assertThat(queryBuilder)
                .isNotNull()
                .hasFieldOrPropertyWithValue("name", "timestamp")
                .hasFieldOrPropertyWithValue("from", Tools.buildElasticSearchTimeFormat(from))
                .hasFieldOrPropertyWithValue("to", Tools.buildElasticSearchTimeFormat(to));
    }

    @Test
    public void determineAffectedIndicesFilterIndexPrefix() throws Exception {
        final DateTime now = DateTime.now(DateTimeZone.UTC);
        final MongoIndexRange indexRange0 = MongoIndexRange.create("graylog_0", now, now.plusDays(1), now, 0);
        final MongoIndexRange indexRange1 = MongoIndexRange.create("graylog_1", now.plusDays(1), now.plusDays(2), now, 0);
        final MongoIndexRange b0 = MongoIndexRange.create("b_0", now.plusDays(1), now.plusDays(2), now, 0);
        final MongoIndexRange b1 = MongoIndexRange.create("b_1", now.plusDays(1), now.plusDays(2), now, 0);
        final SortedSet<IndexRange> indices = ImmutableSortedSet.orderedBy(IndexRange.COMPARATOR)
                .add(indexRange0)
                .add(indexRange1)
                .add(b0)
                .add(b1)
                .build();

        final Stream bStream = mock(Stream.class);

        when(indexRangeService.find(any(DateTime.class), any(DateTime.class))).thenReturn(indices);
        when(streamService.load(eq("123456789ABCDEF"))).thenReturn(bStream);
        final IndexSet indexSet = mock(IndexSet.class);
        when(indexSet.isManagedIndex(startsWith("b_"))).thenReturn(true);
        when(bStream.getIndexSet()).thenReturn(indexSet);

        final TimeRange absoluteRange = AbsoluteRange.create(now.minusDays(1), now.plusDays(1));

        assertThat(searches.determineAffectedIndices(absoluteRange, "streams:123456789ABCDEF"))
                .containsOnly(b0.indexName(), b1.indexName());
    }
}
