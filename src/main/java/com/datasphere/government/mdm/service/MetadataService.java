/*
 * Copyright 2019, Huahuidata, Inc.
 * DataSphere is licensed under the Mulan PSL v1.
 * You can use this software according to the terms and conditions of the Mulan PSL v1.
 * You may obtain a copy of Mulan PSL v1 at:
 * http://license.coscl.org.cn/MulanPSL
 * THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND, EITHER EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT, MERCHANTABILITY OR FIT FOR A PARTICULAR
 * PURPOSE.
 * See the Mulan PSL v1 for more details.
 */

package com.datasphere.government.mdm.service;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Optional;

import com.datasphere.government.mdm.Metadata;
import com.datasphere.government.mdm.MetadataRepository;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.data.rest.core.event.AfterCreateEvent;
import org.springframework.data.rest.core.event.BeforeCreateEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.supercsv.prefs.CsvPreference;

import com.datasphere.common.data.DataGrid;
import com.datasphere.common.data.Row;
import com.datasphere.government.mdm.preview.MetadataEngineDataPreview;
import com.datasphere.government.mdm.preview.MetadataJdbcDataPreview;
import com.datasphere.government.mdm.source.MetaSourceService;
import com.datasphere.government.mdm.source.MetadataSource;
import com.datasphere.server.common.exception.ResourceNotFoundException;
import com.datasphere.server.connections.jdbc.accessor.JdbcAccessor;
import com.datasphere.datasource.DataSource;
import com.datasphere.server.connections.jdbc.JdbcCSVWriter;
import com.datasphere.server.connections.jdbc.JdbcConnectionService;
import com.datasphere.datasource.data.DataSourceValidator;
import com.datasphere.datasource.ingestion.jdbc.JdbcIngestionInfo;
import com.datasphere.datasource.connections.DataConnection;
import com.datasphere.datasource.connections.DataConnectionHelper;
import com.datasphere.server.domain.engine.EngineProperties;
import com.datasphere.server.domain.engine.EngineQueryService;
import com.datasphere.server.domain.storage.StorageProperties;


@Component
@Transactional
public class MetadataService implements ApplicationEventPublisherAware {

  private static Logger LOGGER = LoggerFactory.getLogger(MetadataService.class);

  @Autowired
  MetaSourceService metaSourceService;

  @Autowired
  JdbcConnectionService jdbcConnectionService;

  @Autowired
  StorageProperties storageProperties;

  @Autowired
  EngineProperties engineProperties;

  @Autowired
  MetadataRepository metadataRepository;

  @Autowired
  EngineQueryService engineQueryService;

  @Autowired
  DataSourceValidator dataSourceValidator;

  private ApplicationEventPublisher publisher;

  @Override
  public void setApplicationEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
    this.publisher = applicationEventPublisher;
  }

  /**
   * find metadata from datasource identifier
   */
  @Transactional(readOnly = true)
  public Optional <Metadata> findByDataSource(String dataSourceId) {
    List <Metadata> results = metadataRepository.findBySource(dataSourceId, null, null);
    if (CollectionUtils.isEmpty(results)) {
      return Optional.empty();
    }

    return Optional.of(results.get(0));
  }

  /**
   * Save using datasource information
   */
  public void saveFromDataSource(DataSource dataSource) {
    Optional <Metadata> metadataExist = findByDataSource(dataSource.getId());

    if (metadataExist.isPresent()) {
      return;
    }

    // make metadata from datasource
    Metadata metadata = new Metadata(dataSource);

    metadataRepository.saveAndFlush(metadata);

    LOGGER.info("Successfully saved metadata({}) from datasource({})", metadata.getId(), dataSource.getId());
  }

  /**
   * Update from updated datasource
   */
  public void updateFromDataSource(DataSource dataSource, boolean includeFields) {
    Optional <Metadata> metadata = findByDataSource(dataSource.getId());

    if (!metadata.isPresent()) {
      return;
    }

    Metadata updateMetadata = metadata.get();
    updateMetadata.updateFromDataSource(dataSource, includeFields);

    metadataRepository.save(updateMetadata);
  }

  /**
   * Delete metadata
   */
  public void delete(String... metadataIds) {

    int deleteCnt = 0;
    for (String metadataId : metadataIds) {
      Metadata deletingMetadata = metadataRepository.findById(metadataId).get();
      if (deletingMetadata == null) {
        continue;
      }
      metadataRepository.deleteById(metadataId);
      deleteCnt++;
    }

    LOGGER.info("Successfully delete {} metadata items", deleteCnt);
  }

  @Transactional
  public List<Metadata> createAndReturn(List<Metadata> metadataList){
    List<Metadata> returnBody = new ArrayList<>();
    for(Metadata metadata : metadataList){
      //trigger event
      publisher.publishEvent(new BeforeCreateEvent(metadata));
      Metadata savedObject = metadataRepository.save(metadata);
      publisher.publishEvent(new AfterCreateEvent(savedObject));
      returnBody.add(savedObject);
    }
    return returnBody;
  }

  public DataGrid getDataGrid(Metadata metadata, int limit) {
    DataGrid resultDataGrid = null;
    MetadataSource metadataSource = metadata.getSource();

    //1. SourceType=JDBC
    if (metadataSource.getType() == Metadata.SourceType.JDBC) {
      DataConnection jdbcDataConnection
          = (DataConnection) metaSourceService.getSourcesBySourceId(metadataSource.getType(), metadataSource.getSourceId());
      String query = makeQueryStatementForPreview(metadata);

      MetadataJdbcDataPreview metadataJdbcDataPreview = new MetadataJdbcDataPreview(metadata);
      metadataJdbcDataPreview.setConnectInformation(jdbcDataConnection);
      metadataJdbcDataPreview.setQuery(query);
      metadataJdbcDataPreview.setLimit(limit);
      metadataJdbcDataPreview.getData();

      resultDataGrid = metadataJdbcDataPreview;

    //2. SourceType=ENGINE
    } else if (metadataSource.getType() == Metadata.SourceType.ENGINE) {
      DataSource metadataSourceDetail
          = (DataSource) metaSourceService.getSourcesBySourceId(metadataSource.getType(), metadataSource.getSourceId());

      if(metadataSourceDetail == null){
        throw new ResourceNotFoundException("Metadata Source (" + metadataSource.getType() + " : " + metadataSource.getSourceId() + ") not exist.");
      }

      //2-1. SourceType=ENGINE, ConnectionType=ENGINE
      if(metadataSourceDetail.getConnType() == DataSource.ConnectionType.ENGINE){ //druid
        MetadataEngineDataPreview metadataEngineDataPreview = new MetadataEngineDataPreview(metadata);
        metadataEngineDataPreview.setEngineDataSource(metadataSourceDetail);
        metadataEngineDataPreview.setEngineQueryService(engineQueryService);
        metadataEngineDataPreview.setLimit(limit);
        metadataEngineDataPreview.getData();

        resultDataGrid = metadataEngineDataPreview;

      //2-2. SourceType=ENGINE, ConnectionType=LINK
      } else if (metadataSourceDetail.getConnType() == DataSource.ConnectionType.LINK) { //jdbc
        DataConnection jdbcDataConnection = metadataSourceDetail.getConnection();
        String query = makeQueryStatementForPreview(metadata);
        MetadataJdbcDataPreview metadataJdbcDataPreview = new MetadataJdbcDataPreview(metadata);
        metadataJdbcDataPreview.setConnectInformation(jdbcDataConnection);
        metadataJdbcDataPreview.setQuery(query);
        metadataJdbcDataPreview.setLimit(limit);
        metadataJdbcDataPreview.getData();

        resultDataGrid = metadataJdbcDataPreview;
      }

    //3. SourceType=STAGEDB
    } else if (metadataSource.getType() == Metadata.SourceType.STAGEDB) {
      StorageProperties.StageDBConnection stageDBConnection = storageProperties.getStagedb();

      if (stageDBConnection == null) {
        throw new IllegalArgumentException("Staging Hive DB info. required.");
      }

      DataConnection hiveConnection = new DataConnection();
      hiveConnection.setUrl(stageDBConnection.getUrl());
      hiveConnection.setHostname(stageDBConnection.getHostname());
      hiveConnection.setPort(stageDBConnection.getPort());
      hiveConnection.setUsername(stageDBConnection.getUsername());
      hiveConnection.setPassword(stageDBConnection.getPassword());
      hiveConnection.setImplementor("STAGE");

      String query = makeQueryStatementForPreview(metadata);

      MetadataJdbcDataPreview metadataJdbcDataPreview = new MetadataJdbcDataPreview(metadata);
      metadataJdbcDataPreview.setConnectInformation(hiveConnection);
      metadataJdbcDataPreview.setQuery(query);
      metadataJdbcDataPreview.setLimit(limit);
      metadataJdbcDataPreview.getData();

      resultDataGrid = metadataJdbcDataPreview;
    }

    return resultDataGrid;
  }

  public void getDownloadData(Metadata metadata, String fileName, int limit) {
    DataGrid dataGrid = getDataGrid(metadata, limit);
    try {
      createCSVFile(dataGrid, fileName);
    } catch (Exception e) {
      LOGGER.error("getDownloadData : createCSVFile Exception " + e.getMessage());
    }
  }

  private Connection getConnection(Metadata metadata) {
    MetadataSource metadataSource = metadata.getSource();
    Connection conn = null;

    if (metadataSource.getType() == Metadata.SourceType.JDBC) {

      if (StringUtils.isEmpty(metadataSource.getSourceId())) {
        throw new IllegalArgumentException("DataConnection info. required.");
      }

      DataConnection jdbcDataConnection = (DataConnection) metaSourceService
          .getSourcesBySourceId(metadataSource.getType(), metadataSource.getSourceId());

      JdbcAccessor jdbcDataAccessor = DataConnectionHelper.getAccessor(jdbcDataConnection);
      try {
        conn = jdbcDataAccessor.getConnection();
      } catch (Exception e) {
        LOGGER.error("getConnection : [Type] Metadata.SourceType.JDBC Exception " + e.getMessage());
      }

    } else if (metadataSource.getType() == Metadata.SourceType.ENGINE) {
      DataSource metadataSourceDetail
          = (DataSource) metaSourceService.getSourcesBySourceId(metadataSource.getType(), metadataSource.getSourceId());
      JdbcAccessor jdbcDataAccessor = null;

      if(metadataSourceDetail.getConnType() == DataSource.ConnectionType.ENGINE){ //druid
        DataConnection jdbcDataConnection = new DataConnection();
        jdbcDataConnection.setImplementor("DRUID");
        jdbcDataConnection.setUrl(makeDruidEngineConnectUrl());

        jdbcDataAccessor = DataConnectionHelper.getAccessor(jdbcDataConnection);
      } else if(metadataSourceDetail.getConnType() == DataSource.ConnectionType.LINK){ //jdbc
        jdbcDataAccessor = DataConnectionHelper.getAccessor(metadataSourceDetail.getConnection());
      }

      try {
        conn = jdbcDataAccessor.getConnection();
      } catch (Exception e) {
        LOGGER.error("getConnection : [Type] Metadata.SourceType.ENGINE Exception " + e.getMessage());
      }
    } else if (metadataSource.getType() == Metadata.SourceType.STAGEDB) {
      StorageProperties.StageDBConnection stageDBConnection = storageProperties.getStagedb();

      if (stageDBConnection == null) {
        throw new IllegalArgumentException("Staging Hive DB info. required.");
      }

      DataConnection hiveConnection = new DataConnection();
      hiveConnection.setUrl(stageDBConnection.getUrl());
      hiveConnection.setHostname(stageDBConnection.getHostname());
      hiveConnection.setPort(stageDBConnection.getPort());
      hiveConnection.setUsername(stageDBConnection.getUsername());
      hiveConnection.setPassword(stageDBConnection.getPassword());
      hiveConnection.setImplementor("STAGE");

      JdbcAccessor jdbcDataAccessor = DataConnectionHelper.getAccessor(hiveConnection);

      try {
        conn = jdbcDataAccessor.getConnection();
      } catch (Exception e) {
        LOGGER.error("getConnection : [Type] Metadata.SourceType.STAGEDB Exception " + e.getMessage());
      }
    }

    if (conn == null) {
      throw new IllegalArgumentException("getConnection is null : " + metadataSource.getSourceId());
    }

    return conn;
  }

  public String getDownloadFilePath(String fileName) {
    String downloadFilePath = null;
    String fileDownalodLocalPath = System.getProperty("user.home") + File.separator + "metadatas" + File.separator + "downloads";

    File file = new File(fileDownalodLocalPath);
    if (!file.exists()) {
      file.mkdirs();
    }

    downloadFilePath = fileDownalodLocalPath + File.separator + fileName + "_" + Calendar.getInstance().getTime().getTime() + ".csv";

    if (downloadFilePath == null) {
      throw new IllegalArgumentException("getDownloadFilePath() : downloadFilePath is null ");
    }

    return downloadFilePath;
  }

  private void createCSVFile(DataGrid dataGrid, String filePath) throws IOException{
    BufferedWriter writer = Files.newBufferedWriter(Paths.get(filePath));

    String[] headers = new String[dataGrid.getColumnCount()];
    CSVFormat csvFormat = CSVFormat.DEFAULT.withHeader(dataGrid.getColumnNames().toArray(headers));

    CSVPrinter csvPrinter = new CSVPrinter(writer, csvFormat);

    for(Row row : dataGrid.getRows()){
      csvPrinter.printRecord(row.values);
    }

    csvPrinter.flush();

    LOGGER.debug("created csv file to : {}", filePath);
  }

  private void createCSVFile(Connection connection, String query, String fileName, int limit) throws IOException {

    try {
      JdbcCSVWriter jdbcCSVWriter = new JdbcCSVWriter(new FileWriter(fileName), CsvPreference.STANDARD_PREFERENCE);
      jdbcCSVWriter.setConnection(connection);
      jdbcCSVWriter.setFetchSize(1000);
      jdbcCSVWriter.setMaxRow(limit);
      jdbcCSVWriter.setQuery(query);
      jdbcCSVWriter.setFileName(fileName);
      jdbcCSVWriter.write();
    } catch (Exception e) {
      LOGGER.error(e.getMessage());
    }
  }

  private String makeDruidEngineConnectUrl() {

    String druidHost = engineProperties.getHostname().get("broker");
    StringBuilder stringBuilder = new StringBuilder();

    stringBuilder.append("jdbc:avatica:remote:url=")
        .append(druidHost)
        .append("/druid/v2/sql/avatica/");

    return stringBuilder.toString();
  }

  private String makeQueryStatementForPreview(Metadata metadata) {
    String queryString = null;
    MetadataSource metadataSource = metadata.getSource();

    DataConnection jdbcConnection = null;
    JdbcIngestionInfo.DataType type = null;   //TABLE, QUERY
    String schema = null;
    String query = null;  //tableName or query

    //1. SourceType=ENGINE
    if (metadataSource.getType() == Metadata.SourceType.ENGINE) {
      DataSource metadataSourceDetail
          = (DataSource) metaSourceService.getSourcesBySourceId(metadataSource.getType(), metadataSource.getSourceId());

      //1-1. SourceType=ENGINE, ConnectionType=ENGINE
      if (metadataSourceDetail.getConnType() == DataSource.ConnectionType.ENGINE){ //druid
        jdbcConnection = new DataConnection();
        jdbcConnection.setImplementor("DRUID");
        schema = "druid";
        query = metadataSourceDetail.getEngineName();
        type = JdbcIngestionInfo.DataType.TABLE;

        //1-2. SourceType=ENGINE, ConnectionType=LINKED
      } else if (metadataSourceDetail.getConnType() == DataSource.ConnectionType.LINK){ //jdbc
        JdbcIngestionInfo jdbcInfo = metadataSourceDetail.getIngestionInfoByType();
        jdbcConnection = metadataSourceDetail.getJdbcConnectionForIngestion();
        schema = jdbcInfo.getDatabase();
        query = jdbcInfo.getQuery();
        type = jdbcInfo.getDataType();
      }

    //2. SourceType=JDBC
    } else if (metadataSource.getType() == Metadata.SourceType.JDBC){
      jdbcConnection
          = (DataConnection) metaSourceService.getSourcesBySourceId(metadataSource.getType(), metadataSource.getSourceId());
      schema = metadataSource.getSchema();
      query = metadataSource.getTable();
      type = JdbcIngestionInfo.DataType.TABLE;

    //3. SourceType=STAGEDB
    } else if (metadataSource.getType() == Metadata.SourceType.STAGEDB){
      jdbcConnection = new DataConnection();
      jdbcConnection.setImplementor("STAGE");
      schema = metadataSource.getSchema();
      query = metadataSource.getTable();
      type = JdbcIngestionInfo.DataType.TABLE;
    }

    queryString = jdbcConnectionService.generateSelectQuery(jdbcConnection, schema, type, query, null);
    return queryString;
  }
}
