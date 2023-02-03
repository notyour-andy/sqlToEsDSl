package com.andy.sqltodsl.utils;

import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.SQLLimit;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.statement.SQLSelectItem;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlSelectQueryBlock;
import com.alibaba.druid.sql.dialect.mysql.parser.MySqlStatementParser;
import com.alibaba.druid.sql.dialect.mysql.visitor.MySqlSchemaStatVisitor;
import com.alibaba.druid.sql.parser.EOFParserException;
import com.alibaba.druid.stat.TableStat;
import com.alibaba.druid.util.JdbcConstants;
import com.andy.sqltodsl.bean.models.OrderColumnModel;
import com.google.inject.internal.util.Preconditions;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;

import java.util.*;
import java.util.stream.Collectors;

public class SqlUtils {

    private static final String FIELD = "field";

    private static final String VALUE = "value";

    private static final String OPERATOR = "operator";

    public static void main(String[] args) {
        String sql = "select * from text where a = '1' and b = '2' or (c = '4' and d = '4' or c = '5' ) group by g, f  limit 10, 10";
        List<String> selectList = SqlUtils.getSelectList(sql);
        System.out.println(selectList);
    }



    /**
    *获取limit参数
    *@author Andy
    *@param sql sql语句
    *@date 2022/11/30
    */
    public static Map<String, Object> getLimitArgMap(String sql){
        MySqlSelectQueryBlock queryBlock = getQueryBlock(sql);
        SQLLimit limit = queryBlock.getLimit();
        if(Objects.isNull(limit)){
            return null;
        }else{
            Map<String, Object> returnMap = new HashMap<>();
            returnMap.put("from", Objects.isNull(limit.getOffset()) ? 0 : limit.getOffset().toString());
            returnMap.put("size", limit.getRowCount().toString());
            return returnMap;
        }
    }

    /**
    *获取group by 参数
    *@author Andy
    *@param sql sql语句
    *@date 2022/11/30
    */
    public static List<String> getGroupByFieldList(String sql){
        List<String> resultList = new ArrayList<>();
        MySqlSelectQueryBlock queryBlock = getQueryBlock(sql);
        if (!Objects.isNull(queryBlock.getGroupBy())) {
            for (SQLExpr item : queryBlock.getGroupBy().getItems()) {
                resultList.add(item.toString());
            }
        }
        return resultList;
    }

    /**
    *获取查询的字段
    *@author Andy
    *@date 2023/2/2
    */
    public static List<String> getSelectList(String sql){
        MySqlSelectQueryBlock queryBlock = getQueryBlock(sql);
        List<SQLSelectItem> selectList = queryBlock.getSelectList();
        if (CollectionUtils.isNotEmpty(selectList)){
            //如果含有*,且只能只含有* 这里的filter:当查询字段为空, queryBlock会将整条sql语句作为查询字段, 去除这种情况
            List<String> fieldList = selectList.stream().map(ele -> ele.getExpr().toString())
                                                        .filter(ele -> !(ele.contains("SELECT") && ele.contains("FROM")))
                                                        .collect(Collectors.toList());
            if (CollectionUtils.isNotEmpty(fieldList)) {
                if (fieldList.contains("*")) {
                    if (fieldList.size() == 1) {
                        return Collections.emptyList();
                    } else {
                        throw new IllegalArgumentException("查询字段有误");
                    }
                }
                return fieldList;
            }else{
                throw new IllegalArgumentException("查询字段为空");
            }
        }else{
            throw new IllegalArgumentException("查询字段为空");
        }

    }

    public static MySqlSelectQueryBlock getQueryBlock(String sql){
        List<SQLStatement> sqlStatementList = SQLUtils.parseStatements(sql, JdbcConstants.MYSQL);
        return ((MySqlSelectQueryBlock) (((SQLSelectStatement) sqlStatementList.get(0)).getSelect()).getQuery());
    }


    public static MySqlSchemaStatVisitor getVisitor(String sql){
        //获取visitor
        MySqlStatementParser parser = new MySqlStatementParser(sql);
        SQLStatement sqlStatement = parser.parseStatement();
        MySqlSchemaStatVisitor visitor = new MySqlSchemaStatVisitor();
        sqlStatement.accept(visitor);
        return visitor;
    }



    /**
    *获取排序字段
    *@author Andy
    *@param sql 语句
    *@return 排序字段
    *@date 2022/11/25
    */
    public static List<OrderColumnModel> getOrderColumnList(String sql){
        List<OrderColumnModel> modelList = new ArrayList<>();
        OrderColumnModel model;
        MySqlSchemaStatVisitor visitor = getVisitor(sql);
        for (TableStat.Column column : visitor.getOrderByColumns()) {

            model = new OrderColumnModel();
            model.setName(column.getName());
            //默认增
            model.setOrderType(MapUtils.getString(column.getAttributes(), "orderBy.type", "ASC"));
            modelList.add(model);
        }
        return modelList;
    }


    /**
     * 将sql的查询语句去除空格存入set
     * @author Andy
     * @date 2022/11/14 22:31
     * @param sql 待解析sql
     * @return conditions set
     **/
    public static List<String> parseQueryConditions(String sql){
        List<String> resultSet = new ArrayList<>();
        MySqlSchemaStatVisitor visitor = getVisitor(sql);
        List<String> tableNameList = getTableNameList(visitor);
        for (TableStat.Condition con : visitor.getConditions()) {
            Class<?> aClass = con.getValues().get(0).getClass();
            String fullName = getFieldName(tableNameList, con);
            //Condition对于同一运算符的多个值会存在列表里 textid = (123, 234)
            for (Object value : con.getValues()) {
                //字符串带双引号
                Object realVal = Objects.equals(aClass.getName(), "java.lang.String") ? "'" + value + "'" : value;
                resultSet.add(fullName + con.getOperator() + realVal);
            }
        }
        return resultSet;
    }

    /**
    *获取字段名称
    *@author Andy
    *@param tableNameList 表名列表
    *@param con 查询条件
    *@date 2022/11/30
    */
    private static String getFieldName(List<String> tableNameList, TableStat.Condition con) {
        //单表查询, 普通字段:tableName.fieldName nested字段:field_name.field_name
        String fullName = con.getColumn().getFullName();
        String[] nameArray = fullName.split("\\.");
        if (Objects.equals(nameArray[0], tableNameList.get(0)) && nameArray.length > 1){
            //等于表名, 则去除
            fullName = nameArray[1];
        }
        return fullName;
    }


    /**
    *解析查询条件为MapList
    *@author Andy
    *@param sql 查询语句
    *@return mapList
    *@date 2022/11/15
    */
    public static List<Map<String, Object>> parseQueryConditionsToMapList(String sql){
        List<Map<String, Object>> resultList = new ArrayList<>();
        Map<String, Object> dataMap;
        MySqlSchemaStatVisitor visitor = getVisitor(sql);
        List<String> tableNameList = getTableNameList(visitor);
        for (TableStat.Condition con : visitor.getConditions()) {
            for (Object value : con.getValues()) {
                Class<?> aClass = value.getClass();
                dataMap = new HashMap<>();
                dataMap.put(FIELD, getFieldName(tableNameList, con));
                dataMap.put(VALUE, Objects.equals(aClass.getName(), "java.lang.String") ? "'" + value + "'" : value);
                dataMap.put(OPERATOR, con.getOperator());
                resultList.add(dataMap);
            }
        }
        return resultList;
    }



    /**
    *获得sql中的查询表名
    *@author Andy
    *@param visitor 查询器
    *@return 表名List
    *@date 2022/11/15
    */
    public static List<String> getTableNameList(MySqlSchemaStatVisitor visitor){
        List<String> resultList = new ArrayList<>();
        for (TableStat.Name key : visitor.getTables().keySet()) {
            resultList.add(key.getName());
        }
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(resultList), "未解析到表名");
        return resultList;
    }


    /**
    *获取where完整语句, 只考虑单表单where
    *@author Andy
    *@param sql sql语句
    *@return 完整的where语句
    *@date 2022/11/15
    */
    public static String getWhereStatement(String sql){
        List<SQLStatement> sqlStatementList;
        try {
            sqlStatementList = SQLUtils.parseStatements(sql, JdbcConstants.MYSQL);
        }catch (EOFParserException e){
            throw new RuntimeException("sql语句有误");
        }
        Preconditions.checkArgument(Objects.equals(sqlStatementList.size(), 1), "只支持单WHERE条件查询");
        MySqlSelectQueryBlock mysqlSelectQueryBlock = ((MySqlSelectQueryBlock) (((SQLSelectStatement) sqlStatementList.get(0)).getSelect()).getQuery());
        SQLExpr sqlExpr = mysqlSelectQueryBlock.getWhere();
        String stm = "";
        if (!Objects.isNull(sqlExpr)) {
            stm = sqlExpr.toString();
            //去除多余的符号
            stm = stm.replace("\n", "");
            stm = stm.replace("\t", "");
        }
        return stm;

    }
}
