package com.library.agent.eval.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 百分位与均值计算工具。
 * <p>
 * 项目内无现成的百分位实现，而测评批次汇总需要 P50/P95。百分位采用与
 * numpy.percentile 默认一致的线性插值法（rank=(n-1)*p/100，在相邻两点间插值）。
 */
public final class Percentiles {

    /**
     * 结果保留小数位数
     */
    private static final int SCALE = 2;

    private Percentiles() {
    }

    /**
     * 计算平均值并保留两位小数。
     *
     * @param values 原始数值集合，允许含 null（会被跳过）
     * @return 平均值；集合为空或全为 null 时返回 null
     */
    public static BigDecimal average(List<? extends Number> values) {
        List<Double> nums = toDoubles(values);
        if (nums.isEmpty()) {
            return null;
        }
        double sum = 0;
        for (double num : nums) {
            sum += num;
        }
        return scale(sum / nums.size());
    }

    /**
     * 计算百分位（线性插值）并保留两位小数。
     *
     * @param values     原始数值集合，允许含 null（会被跳过）
     * @param percentile 百分位，取值 0~100（如 95 表示 P95）；越界会被夹取到边界
     * @return 百分位值；集合为空或全为 null 时返回 null
     */
    public static BigDecimal percentile(List<? extends Number> values, double percentile) {
        List<Double> nums = toDoubles(values);
        if (nums.isEmpty()) {
            return null;
        }
        nums.sort(Double::compareTo);
        int size = nums.size();
        if (size == 1) {
            return scale(nums.get(0));
        }
        double clamped = Math.max(0, Math.min(100, percentile));
        double rank = (size - 1) * clamped / 100.0;
        int lower = (int) Math.floor(rank);
        int upper = (int) Math.ceil(rank);
        if (lower == upper) {
            return scale(nums.get(lower));
        }
        double fraction = rank - lower;
        double interpolated = nums.get(lower) + fraction * (nums.get(upper) - nums.get(lower));
        return scale(interpolated);
    }

    /**
     * 提取非 null 数值并转为 double。
     */
    private static List<Double> toDoubles(List<? extends Number> values) {
        List<Double> nums = new ArrayList<>();
        if (values == null) {
            return nums;
        }
        for (Number value : values) {
            if (value != null) {
                nums.add(value.doubleValue());
            }
        }
        return nums;
    }

    private static BigDecimal scale(double value) {
        return BigDecimal.valueOf(value).setScale(SCALE, RoundingMode.HALF_UP);
    }
}
