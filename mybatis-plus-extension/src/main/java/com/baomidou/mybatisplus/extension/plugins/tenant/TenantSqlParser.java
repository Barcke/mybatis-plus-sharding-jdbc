/*
 * Copyright (c) 2011-2020, baomidou (jobob@qq.com).
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.baomidou.mybatisplus.extension.plugins.tenant;

import java.util.List;

import com.baomidou.mybatisplus.core.parser.AbstractJsqlParser;
import com.baomidou.mybatisplus.core.toolkit.Assert;
import com.baomidou.mybatisplus.core.toolkit.ExceptionUtils;
import com.baomidou.mybatisplus.core.toolkit.StringPool;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.ItemsList;
import net.sf.jsqlparser.expression.operators.relational.MultiExpressionList;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.LateralSubSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.SelectBody;
import net.sf.jsqlparser.statement.select.SelectExpressionItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.SubJoin;
import net.sf.jsqlparser.statement.select.SubSelect;
import net.sf.jsqlparser.statement.select.ValuesList;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.statement.update.Update;

/**
 * ?????? SQL ???????????? TenantId ?????? ???
 *
 * @author hubin
 * @since 2017-09-01
 */
@Data
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
public class TenantSqlParser extends AbstractJsqlParser {

    private TenantHandler tenantHandler;

    /**
     * select ????????????
     */
    @Override
    public void processSelectBody(SelectBody selectBody) {
        if (selectBody instanceof PlainSelect) {
            processPlainSelect((PlainSelect) selectBody);
        } else if (selectBody instanceof WithItem) {
            WithItem withItem = (WithItem) selectBody;
            if (withItem.getSelectBody() != null) {
                processSelectBody(withItem.getSelectBody());
            }
        } else {
            SetOperationList operationList = (SetOperationList) selectBody;
            if (operationList.getSelects() != null && operationList.getSelects().size() > 0) {
                operationList.getSelects().forEach(this::processSelectBody);
            }
        }
    }

    /**
     * insert ????????????
     */
    @Override
    public void processInsert(Insert insert) {
        if (tenantHandler.doTableFilter(insert.getTable().getName())) {
            // ??????????????????
            return;
        }
        insert.getColumns().add(new Column(tenantHandler.getTenantIdColumn()));
        if (insert.getSelect() != null) {
            processPlainSelect((PlainSelect) insert.getSelect().getSelectBody(), true);
        } else if (insert.getItemsList() != null) {
            // fixed github pull/295
            ItemsList itemsList = insert.getItemsList();
            if (itemsList instanceof MultiExpressionList) {
                ((MultiExpressionList) itemsList).getExprList().forEach(el -> el.getExpressions().add(tenantHandler.getTenantId()));
            } else {
                ((ExpressionList) insert.getItemsList()).getExpressions().add(tenantHandler.getTenantId());
            }
        } else {
            throw ExceptionUtils.mpe("Failed to process multiple-table update, please exclude the tableName or statementId");
        }
    }

    /**
     * update ????????????
     */
    @Override
    public void processUpdate(Update update) {
        List<Table> tableList = update.getTables();
        Assert.isTrue(null != tableList && tableList.size() < 2,
            "Failed to process multiple-table update, please exclude the statementId");
        Table table = tableList.get(0);
        if (tenantHandler.doTableFilter(table.getName())) {
            // ??????????????????
            return;
        }
        update.setWhere(this.andExpression(table, update.getWhere()));
    }

    /**
     * delete ????????????
     */
    @Override
    public void processDelete(Delete delete) {
        if (tenantHandler.doTableFilter(delete.getTable().getName())) {
            // ??????????????????
            return;
        }
        delete.setWhere(this.andExpression(delete.getTable(), delete.getWhere()));
    }

    /**
     * delete update ?????? where ??????
     */
    protected BinaryExpression andExpression(Table table, Expression where) {
        //??????where???????????????
        EqualsTo equalsTo = new EqualsTo();
        equalsTo.setLeftExpression(this.getAliasColumn(table));
        equalsTo.setRightExpression(tenantHandler.getTenantId());
        if (null != where) {
            if (where instanceof OrExpression) {
                return new AndExpression(equalsTo, new Parenthesis(where));
            } else {
                return new AndExpression(equalsTo, where);
            }
        }
        return equalsTo;
    }

    /**
     * ?????? PlainSelect
     */
    protected void processPlainSelect(PlainSelect plainSelect) {
        processPlainSelect(plainSelect, false);
    }

    /**
     * ?????? PlainSelect
     *
     * @param plainSelect ignore
     * @param addColumn   ?????????????????????,insert into select???????????????
     */
    protected void processPlainSelect(PlainSelect plainSelect, boolean addColumn) {
        FromItem fromItem = plainSelect.getFromItem();
        if (fromItem instanceof Table) {
            Table fromTable = (Table) fromItem;
            if (tenantHandler.doTableFilter(fromTable.getName())) {
                // ??????????????????
                return;
            }
            plainSelect.setWhere(builderExpression(plainSelect.getWhere(), fromTable));
            if (addColumn) {
                plainSelect.getSelectItems().add(new SelectExpressionItem(new Column(tenantHandler.getTenantIdColumn())));
            }
        } else {
            processFromItem(fromItem);
        }
        List<Join> joins = plainSelect.getJoins();
        if (joins != null && joins.size() > 0) {
            joins.forEach(j -> {
                processJoin(j);
                processFromItem(j.getRightItem());
            });
        }
    }

    /**
     * ??????????????????
     */
    protected void processFromItem(FromItem fromItem) {
        if (fromItem instanceof SubJoin) {
            SubJoin subJoin = (SubJoin) fromItem;
            if (subJoin.getJoinList() != null) {
                subJoin.getJoinList().forEach(this::processJoin);
            }
            if (subJoin.getLeft() != null) {
                processFromItem(subJoin.getLeft());
            }
        } else if (fromItem instanceof SubSelect) {
            SubSelect subSelect = (SubSelect) fromItem;
            if (subSelect.getSelectBody() != null) {
                processSelectBody(subSelect.getSelectBody());
            }
        } else if (fromItem instanceof ValuesList) {
            logger.debug("Perform a subquery, if you do not give us feedback");
        } else if (fromItem instanceof LateralSubSelect) {
            LateralSubSelect lateralSubSelect = (LateralSubSelect) fromItem;
            if (lateralSubSelect.getSubSelect() != null) {
                SubSelect subSelect = lateralSubSelect.getSubSelect();
                if (subSelect.getSelectBody() != null) {
                    processSelectBody(subSelect.getSelectBody());
                }
            }
        }
    }

    /**
     * ??????????????????
     */
    protected void processJoin(Join join) {
        if (join.getRightItem() instanceof Table) {
            Table fromTable = (Table) join.getRightItem();
            if (this.tenantHandler.doTableFilter(fromTable.getName())) {
                // ??????????????????
                return;
            }
            join.setOnExpression(builderExpression(join.getOnExpression(), fromTable));
        }
    }

    /**
     * ????????????
     */
    protected Expression builderExpression(Expression expression, Table table) {
        //???????????????
        EqualsTo equalsTo = new EqualsTo();
        equalsTo.setLeftExpression(this.getAliasColumn(table));
        equalsTo.setRightExpression(tenantHandler.getTenantId());
        //??????????????????????????????????????? "and null" ????????????????????????
        if (expression == null) {
            return equalsTo;
        } else {
            if (expression instanceof BinaryExpression) {
                BinaryExpression binaryExpression = (BinaryExpression) expression;
                if (binaryExpression.getLeftExpression() instanceof FromItem) {
                    processFromItem((FromItem) binaryExpression.getLeftExpression());
                }
                if (binaryExpression.getRightExpression() instanceof FromItem) {
                    processFromItem((FromItem) binaryExpression.getRightExpression());
                }
            }
            if (expression instanceof OrExpression) {
                return new AndExpression(equalsTo, new Parenthesis(expression));
            } else {
                return new AndExpression(equalsTo, expression);
            }
        }
    }

    /**
     * ????????????????????????
     * <p>tableName.tenantId ??? tableAlias.tenantId</p>
     *
     * @param table ?????????
     * @return ??????
     */
    protected Column getAliasColumn(Table table) {
        StringBuilder column = new StringBuilder();
        if (null == table.getAlias()) {
            column.append(table.getName());
        } else {
            column.append(table.getAlias().getName());
        }
        column.append(StringPool.DOT);
        column.append(tenantHandler.getTenantIdColumn());
        return new Column(column.toString());
    }
}
