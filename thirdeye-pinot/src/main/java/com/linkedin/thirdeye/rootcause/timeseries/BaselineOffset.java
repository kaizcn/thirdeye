package com.linkedin.thirdeye.rootcause.timeseries;

import com.google.common.base.Preconditions;
import com.linkedin.thirdeye.dataframe.DataFrame;
import com.linkedin.thirdeye.dataframe.util.MetricSlice;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Synthetic baseline from a single, given offset
 */
public class BaselineOffset implements Baseline {
  private final long offset;

  private BaselineOffset(long offset) {
    this.offset = offset;
  }

  @Override
  public List<MetricSlice> scatter(MetricSlice slice) {
    return Collections.singletonList(slice
        .withStart(slice.getStart() + offset)
        .withEnd(slice.getEnd() + offset));
  }

  private Map<MetricSlice, DataFrame> filter(MetricSlice slice, Map<MetricSlice, DataFrame> data) {
    MetricSlice pattern = scatter(slice).get(0);
    DataFrame value = data.get(pattern);

    if (!data.containsKey(pattern)) {
      return Collections.emptyMap();
    }

    return Collections.singletonMap(pattern, value);
  }

  @Override
  public DataFrame gather(MetricSlice slice, Map<MetricSlice, DataFrame> data) {
    Map<MetricSlice, DataFrame> filtered = this.filter(slice, data);

    Preconditions.checkArgument(filtered.size() == 1);

    MetricSlice dataSlice = filtered.entrySet().iterator().next().getKey();
    DataFrame input = new DataFrame(filtered.entrySet().iterator().next().getValue());

    long offset = dataSlice.getStart() - slice.getStart();
    if (offset != this.offset) {
      throw new IllegalArgumentException(String.format("Found slice with invalid offset %d", offset));
    }

    DataFrame output = new DataFrame(input);
    output.addSeries(COL_TIME, output.getLongs(COL_TIME).subtract(this.offset));

    return output;
  }

  /**
   * Returns an instance of BaselineOffset with the given offset.
   *
   * @param offset time offset
   * @return BaselineOffset with given offset
   */
  public static BaselineOffset fromOffset(long offset) {
    return new BaselineOffset(offset);
  }
}
