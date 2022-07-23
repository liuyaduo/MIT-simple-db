package simpledb.execution;

import simpledb.common.Type;
import simpledb.storage.*;

import java.util.*;

/**
 * Knows how to compute some aggregate over a set of StringFields.
 */
public class StringAggregator implements Aggregator {

    private static final long serialVersionUID = 1L;

    private int gbfield;

    private Type gbfieldtype;

    private int afield;

    private Op what;

    // key:gfiled对应的group；value：group内对afield聚合操作的结果
    // private Map<Field, Integer> map;

    // value: group对应的记录数
    private Map<Field, Integer> countMap;

    // 聚合结果的列表（gfield为-1时，Tuple包含aggregateValue（整张表的聚合结果），否则为（groupValue, aggregateValue））
    private Map<Field, Tuple> countTupleMap;

    // 聚合结果对应的TupleDesc
    private TupleDesc aggregateTd;

    /**
     * Aggregate constructor
     * @param gbfield the 0-based index of the group-by field in the tuple, or NO_GROUPING if there is no grouping
     * @param gbfieldtype the type of the group by field (e.g., Type.INT_TYPE), or null if there is no grouping
     * @param afield the 0-based index of the aggregate field in the tuple
     * @param what aggregation operator to use -- only supports COUNT
     * @throws IllegalArgumentException if what != COUNT
     */

    public StringAggregator(int gbfield, Type gbfieldtype, int afield, Op what) throws IllegalArgumentException{
        // some code goes here
        if (what != Op.COUNT) {
            throw new IllegalArgumentException("操作符必须为COUNT！");
        }
        this.gbfield = gbfield;
        this.gbfieldtype = gbfieldtype;
        this.afield = afield;
        this.what = what;

        // this.map = new HashMap<>();
        this.countMap = new HashMap<>();
        this.countTupleMap = new HashMap<>();
    }

    /**
     * Merge a new tuple into the aggregate, grouping as indicated in the constructor
     * @param tup the Tuple containing an aggregate field and a group-by field
     */
    public void mergeTupleIntoGroup(Tuple tup) {
        // some code goes here
        if (aggregateTd == null) {
            aggregateTd = getTupleDesc(tup.getTupleDesc());
        }

        Field groupField = gbfield != Aggregator.NO_GROUPING ? tup.getField(gbfield) : new StringField("NO_GROUPING", 15);
        countMap.put(groupField, countMap.getOrDefault(groupField, 0) + 1);
        Tuple tuple = countTupleMap.getOrDefault(groupField, new Tuple(aggregateTd));
        if (gbfield != Aggregator.NO_GROUPING) {
            tuple.setField(0, groupField);
            tuple.setField(1, new IntField(countMap.get(groupField)));
        } else {
            tuple.setField(0, new IntField(countMap.get(groupField)));
        }
        countTupleMap.put(groupField, tuple);
    }

    /**
     * Create a OpIterator over group aggregate results.
     *
     * @return a OpIterator whose tuples are the pair (groupVal,
     *   aggregateVal) if using group, or a single (aggregateVal) if no
     *   grouping. The aggregateVal is determined by the type of
     *   aggregate specified in the constructor.
     */
    public OpIterator iterator() {
        // some code goes here
        return new TupleIterator(aggregateTd, countTupleMap.values());
    }

    @Override
    public TupleDesc getTupleDesc(TupleDesc childTd) {

        TupleDesc td;
        if (gbfield != Aggregator.NO_GROUPING) {
            td = new TupleDesc(
                    new Type[]{gbfieldtype, Type.INT_TYPE}
            );
        } else {
            td = new TupleDesc(new Type[]{Type.INT_TYPE});
        }
        return td;
    }

}
