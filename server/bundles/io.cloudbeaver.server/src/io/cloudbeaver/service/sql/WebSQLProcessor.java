/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2020 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.cloudbeaver.service.sql;

import io.cloudbeaver.DBWebException;
import io.cloudbeaver.WebAction;
import io.cloudbeaver.model.WebConnectionInfo;
import io.cloudbeaver.model.session.WebSession;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.data.*;
import org.jkiss.dbeaver.model.exec.*;
import org.jkiss.dbeaver.model.impl.AbstractExecutionSource;
import org.jkiss.dbeaver.model.impl.data.DBDValueError;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseItem;
import org.jkiss.dbeaver.model.navigator.DBNNode;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.sql.SQLQuery;
import org.jkiss.dbeaver.model.sql.SQLSyntaxManager;
import org.jkiss.dbeaver.model.struct.DBSDataContainer;
import org.jkiss.dbeaver.model.struct.DBSDataManipulator;
import org.jkiss.dbeaver.model.struct.DBSEntity;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.utils.CommonUtils;

import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Web SQL processor.
 */
public class WebSQLProcessor {

    private static final Log log = Log.getLog(WebSQLProcessor.class);

    private static final int MAX_RESULTS_COUNT = 100;

    private final WebSession webSession;
    private final WebConnectionInfo connection;
    private final SQLSyntaxManager syntaxManager;
    private final Map<String, WebSQLContextInfo> contexts = new LinkedHashMap<>();

    private AtomicInteger contextId = new AtomicInteger();

    WebSQLProcessor(@NotNull  WebSession webSession, @NotNull WebConnectionInfo connection) {
        this.webSession = webSession;
        this.connection = connection;

        syntaxManager = new SQLSyntaxManager();
        syntaxManager.init(connection.getDataSource());
    }

    void dispose() {
        synchronized (contexts) {
            contexts.forEach((s, context) -> context.dispose());
            contexts.clear();
        }
    }

    public WebConnectionInfo getConnection() {
        return connection;
    }

    public WebSession getWebSession() {
        return webSession;
    }

    SQLSyntaxManager getSyntaxManager() {
        return syntaxManager;
    }

    DBCExecutionContext getExecutionContext() {
        return DBUtils.getDefaultContext(connection.getDataSource(), false);
    }

    private DBCExecutionContext getExecutionContext(@NotNull DBSDataContainer dataContainer) {
        return DBUtils.getDefaultContext(dataContainer, false);
    }

    @NotNull
    public WebSQLContextInfo createContext(String defaultCatalog, String defaultSchema) {
        String contextId = String.valueOf(this.contextId.incrementAndGet());
        WebSQLContextInfo contextInfo = new WebSQLContextInfo(this, contextId, defaultCatalog, defaultSchema);
        synchronized (contexts) {
            contexts.put(contextId, contextInfo);
        }
        return contextInfo;
    }

    @Nullable
    public WebSQLContextInfo getContext(@NotNull String contextId) {
        synchronized (contexts) {
            return contexts.get(contextId);
        }
    }

    @NotNull
    public WebSQLContextInfo[] getContexts() {
        synchronized (contexts) {
            return contexts.values().toArray(new WebSQLContextInfo[0]);
        }
    }

    public void destroyContext(@NotNull WebSQLContextInfo context) {
        context.dispose();
        synchronized (contexts) {
            contexts.remove(context.getId());
        }
    }

    @NotNull
    public WebSQLExecuteInfo processQuery(
        @NotNull DBRProgressMonitor monitor,
        @NotNull WebSQLContextInfo contextInfo,
        @NotNull String sql,
        @Nullable WebSQLDataFilter filter,
        @Nullable WebDataFormat dataFormat) throws DBWebException {
        if (filter == null) {
            // Use default filter
            filter = new WebSQLDataFilter();
        }
        long startTime = System.currentTimeMillis();
        WebSQLExecuteInfo executeInfo = new WebSQLExecuteInfo();

        DBSDataContainer dataContainer = new WebSQLQueryDataContainer(connection.getDataSource(), sql);

        DBCExecutionContext context = getExecutionContext(dataContainer);

        {
            DBDDataFilter dataFilter = filter.makeDataFilter();
            if (dataFilter.hasFilters()) {
                sql = context.getDataSource().getSQLDialect().addFiltersToQuery(
                    monitor,
                    context.getDataSource(),
                    sql,
                    dataFilter);
            }
        }

        final WebSQLDataFilter dataFilter = filter;
        final String sqlQueryText = sql;
        SQLQuery sqlQuery = new SQLQuery(context.getDataSource(), sqlQueryText);

        try {
            DBExecUtils.tryExecuteRecover(monitor, connection.getDataSource(), param -> {
                try (DBCSession session = context.openSession(monitor, DBCExecutionPurpose.USER, "Execute SQL")) {
                    AbstractExecutionSource source = new AbstractExecutionSource(
                        dataContainer,
                        session.getExecutionContext(),
                        WebSQLProcessor.this,
                        sqlQuery);
                    try (DBCStatement dbStat = DBUtils.makeStatement(
                        source,
                        session,
                        DBCStatementType.SCRIPT,
                        sqlQuery,
                        dataFilter.getOffset(),
                        dataFilter.getLimit()))
                    {
                        boolean hasResultSet = dbStat.executeStatement();
                        fillQueryResults(contextInfo, dataContainer, dbStat, hasResultSet, executeInfo, dataFilter, dataFormat);
                    } catch (DBCException e) {
                        throw new InvocationTargetException(e);
                    }
                }
            });
        } catch (DBException e) {
            throw new DBWebException("Error executing query", e);
        }
        executeInfo.setDuration(System.currentTimeMillis() - startTime);
        if (executeInfo.getResults().length == 0) {
            executeInfo.setStatusMessage("No Data");
        } else {
            executeInfo.setStatusMessage("Success");
        }

        return executeInfo;
    }

    @NotNull
    public WebSQLExecuteInfo readDataFromContainer(
        @NotNull WebSQLContextInfo contextInfo,
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBSDataContainer dataContainer,
        @NotNull WebSQLDataFilter filter,
        @Nullable WebDataFormat dataFormat) throws DBException {

        WebSQLExecuteInfo executeInfo = new WebSQLExecuteInfo();

        DBCExecutionContext executionContext = getExecutionContext(dataContainer);
        DBDDataFilter dataFilter = filter.makeDataFilter();
        DBExecUtils.tryExecuteRecover(monitor, connection.getDataSource(), param -> {
            try (DBCSession session = executionContext.openSession(monitor, DBCExecutionPurpose.USER, "Read data from container")) {
                try (WebDataReceiver dataReceiver = new WebDataReceiver(contextInfo, dataContainer)) {
                    DBCStatistics statistics = dataContainer.readData(
                        new WebExecutionSource(dataContainer, executionContext, this),
                        session,
                        dataReceiver,
                        dataFilter,
                        filter.getOffset(),
                        filter.getLimit(),
                        DBSDataContainer.FLAG_NONE,
                        filter.getLimit());
                    executeInfo.setDuration(statistics.getTotalTime());

                    WebSQLQueryResults results = new WebSQLQueryResults(dataFormat);
                    results.setResultSet(dataReceiver.getResultSet());
                    executeInfo.setResults(new WebSQLQueryResults[]{results});

                    executeInfo.setStatusMessage(dataReceiver.getResultSet().getRows().length + " row(s) fetched");
                } catch (DBCException e) {
                    throw new InvocationTargetException(e);
                }
            }
        });
        return executeInfo;
    }

    @WebAction
    public WebSQLExecuteInfo updateResultsData(
        @NotNull WebSQLContextInfo contextInfo,
        @NotNull String resultsId,
        @NotNull List<Object> updateRow,
        @NotNull Map<String, Object> updateValues, WebDataFormat dataFormat) throws DBWebException
    {
        WebSQLResultsInfo resultsInfo = contextInfo.getResults(resultsId);

        DBDRowIdentifier rowIdentifier = resultsInfo.getDefaultRowIdentifier();
        if (rowIdentifier == null) {
            throw new DBWebException("Can't detect row identifier for results '" + resultsId + "'");
        }
        DBSEntity dataContainer = rowIdentifier.getEntity();
        if (!(dataContainer instanceof DBSDataManipulator)) {
            throw new DBWebException("Data container '" + dataContainer.getName() + "' is not editable");
        }
        DBSDataManipulator dataManipulator = (DBSDataManipulator) dataContainer;

        DBRProgressMonitor monitor = webSession.getProgressMonitor();
        WebSQLExecuteInfo result = new WebSQLExecuteInfo();

        try {
            DBExecUtils.tryExecuteRecover(monitor, connection.getDataSource(), param -> {
                try {
                    DBCExecutionContext executionContext = getExecutionContext(dataManipulator);
                    try (DBCSession session = executionContext.openSession(monitor, DBCExecutionPurpose.USER, "Update data in container")) {
                        WebExecutionSource executionSource = new WebExecutionSource(dataManipulator, executionContext, this);

                        DBDAttributeBinding[] allAttributes = resultsInfo.getAttributes();
                        DBDAttributeBinding[] keyAttributes = rowIdentifier.getAttributes().toArray(new DBDAttributeBinding[0]);
                        DBDAttributeBinding[] updateAttributes = new DBDAttributeBinding[updateValues.size()];
                        // Final row is what we return back
                        Object[] finalRow = updateRow.toArray();

                        int index = 0;
                        for (String indexStr : updateValues.keySet()) {
                            int attrIndex = CommonUtils.toInt(indexStr, -1);
                            updateAttributes[index++] = allAttributes[attrIndex];
                        }

                        Object[] rowValues = new Object[updateAttributes.length + keyAttributes.length];
                        for (int i = 0; i < updateAttributes.length; i++) {
                            DBDAttributeBinding updateAttribute = updateAttributes[i];
                            Object cellRawValue = updateValues.get(String.valueOf(updateAttribute.getOrdinalPosition()));
                            Object realCellValue = updateAttribute.getValueHandler().getValueFromObject(session, updateAttribute, cellRawValue, false, true);
                            rowValues[i] = realCellValue;
                            finalRow[updateAttribute.getOrdinalPosition()] = WebSQLUtils.makeWebCellValue(monitor, null, realCellValue);
                        }
                        for (int i = 0; i < keyAttributes.length; i++) {
                            DBDAttributeBinding keyAttribute = keyAttributes[i];
                            Object cellValueRaw = updateRow.get(keyAttribute.getOrdinalPosition());
                            rowValues[updateAttributes.length + i] = keyAttribute.getValueHandler().getValueFromObject(session, keyAttribute, cellValueRaw, false, true);
                        }

                        DBSDataManipulator.ExecuteBatch updateBatch = dataManipulator.updateData(session, updateAttributes, keyAttributes, null, executionSource);
                        updateBatch.add(rowValues);
                        DBCStatistics statistics = updateBatch.execute(session, Collections.emptyMap());

                        // Returns fake resultset with updated row values
                        WebSQLQueryResultSet updatedResultSet = new WebSQLQueryResultSet();
                        updatedResultSet.setResultsInfo(resultsInfo);
                        updatedResultSet.setColumns(resultsInfo.getAttributes());
                        updatedResultSet.setRows(new Object[][]{finalRow});

                        WebSQLQueryResults updateResults = new WebSQLQueryResults(dataFormat);
                        updateResults.setUpdateRowCount(statistics.getRowsUpdated());
                        updateResults.setResultSet(updatedResultSet);
                        result.setDuration(statistics.getExecuteTime());
                        result.setResults(new WebSQLQueryResults[]{updateResults});
                    }
                } catch (Exception e) {
                    throw new InvocationTargetException(e);
                }
            });
        } catch (DBException e) {
            throw new DBWebException("Error updating data", e);
        }
        return result;
    }

    @WebAction
    public WebSQLExecuteInfo updateResultsDataBatch(
        @NotNull WebSQLContextInfo contextInfo,
        @NotNull String resultsId,
        @Nullable List<WebSQLResultsRow> updatedRows,
        @Nullable List<WebSQLResultsRow> deletedRows,
        @Nullable List<WebSQLResultsRow> addedRows,
        @Nullable WebDataFormat dataFormat) throws DBWebException
    {
        WebSQLResultsInfo resultsInfo = contextInfo.getResults(resultsId);

        DBDRowIdentifier rowIdentifier = resultsInfo.getDefaultRowIdentifier();
        if (rowIdentifier == null) {
            throw new DBWebException("Can't detect row identifier for results '" + resultsId + "'");
        }
        DBSEntity dataContainer = rowIdentifier.getEntity();
        if (!(dataContainer instanceof DBSDataManipulator)) {
            throw new DBWebException("Data container '" + dataContainer.getName() + "' is not editable");
        }
        DBSDataManipulator dataManipulator = (DBSDataManipulator) dataContainer;

        DBRProgressMonitor monitor = webSession.getProgressMonitor();
        WebSQLExecuteInfo result = new WebSQLExecuteInfo();
        List<WebSQLQueryResults> queryResults = new ArrayList<>();

        try {
            DBExecUtils.tryExecuteRecover(monitor, connection.getDataSource(), param -> {
                try {
                    DBCExecutionContext executionContext = getExecutionContext(dataManipulator);
                    try (DBCSession session = executionContext.openSession(monitor, DBCExecutionPurpose.USER, "Update rows in container")) {
                        WebExecutionSource executionSource = new WebExecutionSource(dataManipulator, executionContext, this);

                        DBDAttributeBinding[] allAttributes = resultsInfo.getAttributes();
                        DBDAttributeBinding[] keyAttributes = rowIdentifier.getAttributes().toArray(new DBDAttributeBinding[0]);

                        if (!CommonUtils.isEmpty(updatedRows)) {
                            WebSQLQueryResultSet updatedResultSet = new WebSQLQueryResultSet();
                            updatedResultSet.setResultsInfo(resultsInfo);
                            updatedResultSet.setColumns(resultsInfo.getAttributes());
                            long totalUpdateCount = 0;

                            List <Object[]> resultRows = new ArrayList<>();

                            for (WebSQLResultsRow row : updatedRows) {
                                Map<String, Object> updateValues = row.getUpdateValues();
                                if (CommonUtils.isEmpty(row.getData()) || CommonUtils.isEmpty(updateValues)) {
                                    continue;
                                }
                                DBDAttributeBinding[] updateAttributes = new DBDAttributeBinding[updateValues.size()];
                                // Final row is what we return back
                                Object[] finalRow = row.getData().toArray();

                                int index = 0;
                                for (String indexStr : updateValues.keySet()) {
                                    int attrIndex = CommonUtils.toInt(indexStr, -1);
                                    updateAttributes[index++] = allAttributes[attrIndex];
                                }

                                Object[] rowValues = new Object[updateAttributes.length + keyAttributes.length];
                                for (int i = 0; i < updateAttributes.length; i++) {
                                    DBDAttributeBinding updateAttribute = updateAttributes[i];
                                    Object cellRawValue = updateValues.get(String.valueOf(updateAttribute.getOrdinalPosition()));
                                    Object realCellValue = updateAttribute.getValueHandler().getValueFromObject(session, updateAttribute, cellRawValue, false, true);
                                    rowValues[i] = realCellValue;
                                    finalRow[updateAttribute.getOrdinalPosition()] = WebSQLUtils.makeWebCellValue(monitor, null, realCellValue);
                                }
                                for (int i = 0; i < keyAttributes.length; i++) {
                                    DBDAttributeBinding keyAttribute = keyAttributes[i];
                                    Object cellValueRaw = finalRow[keyAttribute.getOrdinalPosition()];
                                    rowValues[updateAttributes.length + i] = keyAttribute.getValueHandler().getValueFromObject(session, keyAttribute, cellValueRaw, false, true);
                                }

                                DBSDataManipulator.ExecuteBatch updateBatch = dataManipulator.updateData(session, updateAttributes, keyAttributes, null, executionSource);
                                updateBatch.add(rowValues);
                                DBCStatistics statistics = updateBatch.execute(session, Collections.emptyMap());

                                totalUpdateCount += statistics.getRowsUpdated();
                                result.setDuration(result.getDuration() + statistics.getExecuteTime());
                                resultRows.add(finalRow);
                            }

                            WebSQLQueryResults updateResults = new WebSQLQueryResults(dataFormat);
                            updateResults.setUpdateRowCount(totalUpdateCount);
                            updateResults.setResultSet(updatedResultSet);
                            updatedResultSet.setRows(resultRows.toArray(new Object[0][]));

                            queryResults.add(updateResults);
                        }

                        if (!CommonUtils.isEmpty(addedRows)) {
                            throw new DBCException("New row add is not supported");
                        }

                        if (!CommonUtils.isEmpty(deletedRows)) {
                            throw new DBCException("Row delete is not supported");
                        }
                    }
                } catch (Exception e) {
                    throw new InvocationTargetException(e);
                }
            });
        } catch (DBException e) {
            throw new DBWebException("Error updating data", e);
        }
        result.setResults(queryResults.toArray(new WebSQLQueryResults[0]));
        return result;
    }

    @NotNull
    public <T> T getDataContainerByNodePath(DBRProgressMonitor monitor, @NotNull String containerPath, Class<T> type) throws DBException {
        DBNNode node = webSession.getNavigatorModel().getNodeByPath(monitor, containerPath);
        if (node == null) {
            throw new DBWebException("Container node '" + containerPath + "' not found");
        }
        if (!(node instanceof DBNDatabaseItem)) {
            throw new DBWebException("Container node '" + containerPath + "' is not a database item");
        }
        DBSObject object = ((DBNDatabaseItem) node).getObject();
        if (!type.isInstance(object)) {
            throw new DBWebException("Container node '" + containerPath + "' doesn't implement " + type.getName());
        }
        return type.cast(object);
    }


    private void fillQueryResults(
        @NotNull WebSQLContextInfo contextInfo, @NotNull DBSDataContainer dataContainer,
        @NotNull DBCStatement dbStat,
        boolean hasResultSet,
        @NotNull WebSQLExecuteInfo executeInfo,
        @NotNull WebSQLDataFilter filter,
        @Nullable WebDataFormat dataFormat) throws DBCException {

        List<WebSQLQueryResults> resultList = new ArrayList<>();
        for (int i = 0; i < MAX_RESULTS_COUNT; i++) {
            WebSQLQueryResults results = new WebSQLQueryResults(dataFormat);
            if (hasResultSet) {
                DBCResultSet resultSet = dbStat.openResultSet();
                if (resultSet == null) {
                    break;
                }
                try (WebDataReceiver dataReceiver = new WebDataReceiver(contextInfo, dataContainer)) {
                    readResultSet(dbStat.getSession(), resultSet, filter, dataReceiver);
                    results.setResultSet(dataReceiver.getResultSet());
                }
            } else {
                int updateRowCount = dbStat.getUpdateRowCount();
                if (updateRowCount >= 0) {
                    results.setUpdateRowCount(updateRowCount);
                } else {
                    break;
                }
            }
            resultList.add(results);
            hasResultSet = dbStat.nextResults();
        }

        executeInfo.setResults(resultList.toArray(new WebSQLQueryResults[0]));
    }

    private void readResultSet(@NotNull DBCSession session, @NotNull DBCResultSet dbResult, @NotNull WebSQLDataFilter filter, @NotNull WebDataReceiver dataReceiver) throws DBCException {
        dataReceiver.fetchStart(session, dbResult, filter.getOffset(), filter.getLimit());
        int rowCount = 0;
        while (dbResult.nextRow()) {
            if (rowCount > filter.getLimit()) {
                break;
            }

            dataReceiver.fetchRow(session, dbResult);
            rowCount++;
        }
        dataReceiver.fetchEnd(session, dbResult);
    }

    ///////////////////////////////////////////////////////
    // Utils

    private class WebDataReceiver implements DBDDataReceiver {
        private final WebSQLContextInfo contextInfo;
        private DBSDataContainer dataContainer;
        private WebSQLQueryResultSet webResultSet = new WebSQLQueryResultSet();

        private DBDAttributeBinding[] bindings;
        private List<Object[]> rows = new ArrayList<>();

        public WebDataReceiver(WebSQLContextInfo contextInfo, DBSDataContainer dataContainer) {
            this.contextInfo = contextInfo;
            this.dataContainer = dataContainer;
        }

        public WebSQLQueryResultSet getResultSet() {
            return webResultSet;
        }

        @Override
        public void fetchStart(DBCSession session, DBCResultSet dbResult, long offset, long maxRows) throws DBCException {
            DBCResultSetMetaData meta = dbResult.getMeta();
            List<DBCAttributeMetaData> attributes = meta.getAttributes();
            bindings = new DBDAttributeBindingMeta[attributes.size()];
            for (int i = 0; i < attributes.size(); i++) {
                DBCAttributeMetaData attrMeta = attributes.get(i);
                bindings[i] = new DBDAttributeBindingMeta(dataContainer, dbResult.getSession(), attrMeta);
            }
        }

        @Override
        public void fetchRow(DBCSession session, DBCResultSet resultSet) throws DBCException {
            Object[] row = new Object[bindings.length];

            for (int i = 0; i < bindings.length; i++) {
                DBDAttributeBinding binding = bindings[i];
                try {
                    Object cellValue = binding.getValueHandler().fetchValueObject(
                        resultSet.getSession(),
                        resultSet,
                        binding.getMetaAttribute(),
                        i);
                    row[i] = cellValue;
                } catch (Throwable e) {
                    row[i] = new DBDValueError(e);
                }
            }

            rows.add(row);
        }

        @Override
        public void fetchEnd(DBCSession session, DBCResultSet resultSet) throws DBCException {

            DBSEntity entity = dataContainer instanceof DBSEntity ? (DBSEntity) dataContainer : null;

            try {
                DBExecUtils.bindAttributes(session, entity, resultSet, bindings, rows);
            } catch (DBException e) {
                log.error("Error binding attributes", e);
            }

            convertComplexValuesToRelationalView(session);

            // Convert row values
            for (Object[] row : rows) {
                for (int i = 0; i < bindings.length; i++) {
                    DBDAttributeBinding binding = bindings[i];
                    row[i] = WebSQLUtils.makeWebCellValue(session.getProgressMonitor(), binding, row[i]);
                }
            }

            webResultSet.setColumns(bindings);
            webResultSet.setRows(rows.toArray(new Object[0][]));

            WebSQLResultsInfo resultsInfo = contextInfo.saveResult(dataContainer, bindings);
            webResultSet.setResultsInfo(resultsInfo);
        }

        private void convertComplexValuesToRelationalView(DBCSession session) {
            // Here we get leaf attributes and refetch them into plain tabl structure
            List<DBDAttributeBinding> leafBindings = new ArrayList<>();
            for (DBDAttributeBinding attr : bindings) {
                collectLeafBindings(attr, leafBindings);
            }
            if (CommonUtils.equalObjects(bindings, leafBindings)) {
                // No complex types
                return;
            }

            // Convert original rows into new rows with leaf attributes
            // Extract values for leaf attributes from original row
            DBDAttributeBinding[] leafAttributes = leafBindings.toArray(new DBDAttributeBinding[0]);
            List<Object[]> newRows = new ArrayList<>();
            for (Object[] row : rows) {
                Object[] newRow = new Object[leafBindings.size()];
                for (int i = 0; i < leafBindings.size(); i++) {
                    DBDAttributeBinding leafAttr = leafBindings.get(i);
                    try {
                        //Object topValue = row[leafAttr.getTopParent().getOrdinalPosition()];
                        Object cellValue = DBUtils.getAttributeValue(leafAttr, leafAttributes, row);
/*
                        Object cellValue = leafAttr.getValueHandler().getValueFromObject(
                            session,
                            leafAttr,
                            topValue,
                            false,
                            false);
*/
                        newRow[i] = cellValue;
                    } catch (Exception e) {
                        newRow[i] = new DBDValueError(e);
                    }
                }
                newRows.add(newRow);
            }
            this.bindings = leafAttributes;
            this.rows = newRows;
        }

        private void collectLeafBindings(DBDAttributeBinding attr, List<DBDAttributeBinding> leafBindings) {
            List<DBDAttributeBinding> nestedBindings = attr.getNestedBindings();
            if (CommonUtils.isEmpty(nestedBindings)) {
                leafBindings.add(attr);
            } else {
                for (DBDAttributeBinding nested : nestedBindings) {
                    collectLeafBindings(nested, leafBindings);
                }
            }
        }

        @Override
        public void close() {
            rows.clear();
        }
    }

}
