package com.wavefront.ingester;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.Supplier;

import com.google.common.collect.ImmutableList;
import com.tdunning.math.stats.AVLTreeDigest;
import com.tdunning.math.stats.Centroid;
import com.tdunning.math.stats.TDigest;
import com.wavefront.data.ParseException;
import com.wavefront.data.TooManyCentroidException;

import org.apache.commons.lang.time.DateUtils;
import wavefront.report.Histogram;
import wavefront.report.HistogramType;
import wavefront.report.ReportHistogram;

import static com.wavefront.ingester.IngesterContext.DEFAULT_HISTOGRAM_COMPRESS_LIMIT_RATIO;

/**
 * Decoder that takes in histograms of the type:
 *
 * [BinType] [Timestamp] [Centroids] [Metric] [Annotations]
 *
 * @author Tim Schmidt (tim@wavefront.com).
 */
public class ReportHistogramDecoder implements ReportableEntityDecoder<String, ReportHistogram> {

  private static final AbstractIngesterFormatter<ReportHistogram> FORMAT =
      ReportHistogramIngesterFormatter.newBuilder().
          caseSensitiveLiterals(ImmutableList.of("!M", "!H", "!D"),
              ReportHistogramDecoder::setBinType).
          optionalTimestamp(ReportHistogram::setTimestamp).
          centroids().
          text(ReportHistogram::setMetric).
          annotationList(ReportHistogram::setAnnotations).
          build();

  private final Supplier<String> defaultHostNameSupplier;

  public ReportHistogramDecoder() {
    this("unknown");
  }

  public ReportHistogramDecoder(String defaultHostName) {
    this(() -> defaultHostName);
  }

  public ReportHistogramDecoder(Supplier<String> defaultHostNameSupplier) {
    this.defaultHostNameSupplier = defaultHostNameSupplier;
  }

  @Override
  public void decode(String msg, List<ReportHistogram> out, String customerId,
                     @Nullable IngesterContext ctx) {
    ReportHistogram histogram = FORMAT.drive(msg, defaultHostNameSupplier, customerId, null,null, null, ctx);
    if (histogram != null) {
      Histogram value = histogram.getValue();
      if (ctx != null) {
        if (value.getCounts().size() > ctx.getHistogramCentroidsLimit()) {
          throw new TooManyCentroidException("Too many centroids (max: " +
              ctx.getHistogramCentroidsLimit() + ")");
        }
        if (ctx.isOptimizeHistograms()) {
          optimizeForStorage(value.getBins(), value.getCounts(), value.getCounts().size(),
              ctx.getTargetHistogramAccuracy());
        }
      }
      // adjust timestamp according to histogram bin first
      long duration = value.getDuration();
      histogram.setTimestamp((histogram.getTimestamp() / duration) * duration);
      out.add(ReportHistogram.newBuilder(histogram).build());
    }
  }

  @Override
  public void decode(String msg, List<ReportHistogram> out) {
    throw new UnsupportedOperationException("Customer ID extraction is not supported");
  }

  private static void setBinType(ReportHistogram target, String binType) {
    int durationMillis;
    switch (binType) {
      case "!M":
        durationMillis = (int) DateUtils.MILLIS_PER_MINUTE;
        break;
      case "!H":
        durationMillis = (int) DateUtils.MILLIS_PER_HOUR;
        break;
      case "!D":
        durationMillis = (int) DateUtils.MILLIS_PER_DAY;
        break;
      default:
        throw new ParseException("Unknown BinType " + binType);
    }
    Histogram histogram = new Histogram();
    histogram.setDuration(durationMillis);
    histogram.setType(HistogramType.TDIGEST);
    target.setValue(histogram);
  }

  /**
   * Optimize the means/counts pair if necessary.
   *
   * @param means  centroids means
   * @param counts centroid counts
   */
  private static void optimizeForStorage(@Nullable List<Double> means,
                                         @Nullable List<Integer> counts,
                                         int size, int storageAccuracy) {
    if (means == null || means.isEmpty() || counts == null || counts.isEmpty()) {
      return;
    }

    if (size > DEFAULT_HISTOGRAM_COMPRESS_LIMIT_RATIO * storageAccuracy) { // Too many centroids
      rewrite(means, counts, storageAccuracy);
    }

    if (counts.stream().anyMatch(i -> i < 1)) { // Bogus counts
      rewrite(means, counts, storageAccuracy);
    } else {
      int strictlyIncreasingLength = 1;
      for (; strictlyIncreasingLength < means.size(); ++strictlyIncreasingLength) {
        if (means.get(strictlyIncreasingLength - 1) >= means.get(strictlyIncreasingLength)) {
          break;
        }
      }
      if (strictlyIncreasingLength != means.size()) { // not ordered
        rewrite(means, counts, storageAccuracy);
      }
    }
  }

  /**
   * Reorganizes a mean/count array pair (such that centroids) are in strictly ascending order.
   *
   * @param means  centroids means
   * @param counts centroid counts
   */
  private static void rewrite(List<Double> means, List<Integer> counts, int storageAccuracy) {
    TDigest temp = new AVLTreeDigest(storageAccuracy);
    int size = Math.min(means.size(), counts.size());
    for (int i = 0; i < size; ++i) {
      int count = counts.get(i);
      if (count > 0) {
        temp.add(means.get(i), count);
      }
    }
    temp.compress();

    means.clear();
    counts.clear();
    for (Centroid c : temp.centroids()) {
      means.add(c.mean());
      counts.add(c.count());
    }
  }
}
