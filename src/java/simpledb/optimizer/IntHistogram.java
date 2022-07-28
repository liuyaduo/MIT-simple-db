package simpledb.optimizer;

import simpledb.execution.Predicate;

import java.util.Map;

/** A class to represent a fixed-width histogram over a single integer-based field.
 */
public class IntHistogram {

    private int bucketsNum;

    private int min;

    private int max;

    // 直方图的桶数组
    private int[] buckets;

    // 桶的宽度
    private double width;

    // 加入直方图的元组的个数
    private int nTuples;


    /**
     * Create a new IntHistogram.
     * 
     * This IntHistogram should maintain a histogram of integer values that it receives.
     * It should split the histogram into "buckets" buckets.
     * 
     * The values that are being histogrammed will be provided one-at-a-time through the "addValue()" function.
     * 
     * Your implementation should use space and have execution time that are both
     * constant with respect to the number of values being histogrammed.  For example, you shouldn't 
     * simply store every value that you see in a sorted list.
     * 
     * @param buckets The number of buckets to split the input value into.
     * @param min The minimum integer value that will ever be passed to this class for histogramming
     * @param max The maximum integer value that will ever be passed to this class for histogramming
     */
    public IntHistogram(int buckets, int min, int max) {
    	// some code goes here
        this.bucketsNum = buckets;
        this.min = min;
        this.max = max;
        this.buckets = new int[buckets];
        this.width = Math.ceil((max - min + 1) / (double) buckets);
    }

    /**
     * Add a value to the set of values that you are keeping a histogram of.
     * @param v Value to add to the histogram
     */
    public void addValue(int v) {
    	// some code goes here
        if (v >= min && v <= max) {
            int index = getIndex(v);
            buckets[index] ++;
            nTuples ++;
        }
    }

    /**
     * Estimate the selectivity of a particular predicate and operand on this table.
     * 
     * For example, if "op" is "GREATER_THAN" and "v" is 5, 
     * return your estimate of the fraction of elements that are greater than 5.
     * 
     * @param op Operator
     * @param v Value
     * @return Predicted selectivity of this particular operator and value
     */
    public double estimateSelectivity(Predicate.Op op, int v) {
        // some code goes here

        double selectivity = 0;
        int bucketIndex = getIndex(v);
        int[] range = getRange(bucketIndex);

        if (op.toString().equals("=")) {
            if (v >= min && v <= max) {
                selectivity = (buckets[bucketIndex] / width) / nTuples;
            }
        } else if (op.toString().equals(">")) {
            if (v < min) {
                selectivity = 1;
            } else if (v < max) {
                selectivity += (range[1] - v) / width * buckets[bucketIndex] / nTuples;
                for (int i = bucketIndex + 1; i < bucketsNum; i++) {
                    selectivity += (double) buckets[i] / nTuples;
                }
            }
        } else if (op.toString().equals("<")) {
            if (v > max) {
                selectivity = 1;
            }else if (v > min) {
                selectivity += (v - range[0]) / width * buckets[bucketIndex] / nTuples;
                for (int i = 0; i < bucketIndex; i++) {
                    selectivity += (double) buckets[i] / nTuples;
                }
            }
        } else if (op.toString().equals(">=")) {
            if (v <= min) {
                selectivity = 1;
            } else if (v <= max) {
                selectivity += (range[1] - v + 1) / width * buckets[bucketIndex] / nTuples;
                for (int i = bucketIndex + 1; i < bucketsNum; i++) {
                    selectivity += (double) buckets[i] / nTuples;
                }
            }
        } else if (op.toString().equals("<=")) {
            if (v >= max) {
                selectivity = 1;
            } else if (v >= min) {
                selectivity += (v - range[0] + 1) / width * buckets[bucketIndex] / nTuples;
                for (int i = 0; i < bucketIndex; i++) {
                    selectivity += (double) buckets[i] / nTuples;
                }
            }
        } else if (op.toString().equals("<>")) {
            if (v < min || v > max) {
                selectivity = 1;
            } else {
                selectivity = 1 - (buckets[bucketIndex] / width) / nTuples;
            }

        } else {
            System.out.println("不合适的操作符！");
        }

        return selectivity;
    }
    
    /**
     * @return
     *     the average selectivity of this histogram.
     *     
     *     This is not an indispensable method to implement the basic
     *     join optimization. It may be needed if you want to
     *     implement a more efficient optimization
     * */
    public double avgSelectivity()
    {
        // some code goes here
        return 1;
    }
    
    /**
     * @return A string describing this histogram, for debugging purposes
     */
    public String toString() {
        // some code goes here
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < buckets.length; i++) {
            int[] range = getRange(i);
            sb.append("[" + range[0] + " : " + range[1] + "] -> " + buckets[i] + "\n");
        }
        return sb.toString();
    }

    /**
     * 返回第i个桶的范围
     * @param i
     * @return [start, end] (左闭右闭)
     */
    private int[] getRange(int i) {
        if (i < 0 || i >= bucketsNum) {
            System.out.println(i + " 超出了桶号的范围！");
        }
        int start = min + i * (int) width;
        return new int[]{start, start + (int) width - 1};
    }

    /**
     * 计算值v对应的桶号
     * @param v
     * @return 第几个桶
     */
    private int getIndex(int v) {
        if (v < min || v > max) {
            System.out.println(v + "超出了" + "[" + min + ":" + max + "]" + "范围");
        }
        int index = (v - min) / (int) width;
        return index;
    }

}
