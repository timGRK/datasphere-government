/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.datasphere.government.mdm;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.rest.core.annotation.HandleAfterCreate;
import org.springframework.data.rest.core.annotation.HandleAfterSave;
import org.springframework.data.rest.core.annotation.HandleBeforeCreate;
import org.springframework.data.rest.core.annotation.HandleBeforeDelete;
import org.springframework.data.rest.core.annotation.RepositoryEventHandler;

import com.datasphere.government.mdm.source.MetaSourceService;
import com.datasphere.government.mdm.source.MetadataSource;
import com.datasphere.server.common.GlobalObjectMapper;
import com.datasphere.server.common.datasource.DataType;
import com.datasphere.server.common.datasource.LogicalType;
import com.datasphere.server.common.exception.ResourceNotFoundException;
import com.datasphere.server.connections.jdbc.accessor.JdbcAccessor;
import com.datasphere.server.datasource.DataSource;
import com.datasphere.server.datasource.DataSourceService;
import com.datasphere.server.datasource.Field;
import com.datasphere.server.datasource.connection.jdbc.HiveTableInformation;
import com.datasphere.server.datasource.connection.jdbc.JdbcConnectionService;
import com.datasphere.server.domain.dataconnection.DataConnection;
import com.datasphere.server.domain.dataconnection.DataConnectionHelper;
import com.datasphere.server.domain.dataconnection.accessor.HiveDataAccessor;
import com.datasphere.server.domain.engine.EngineProperties;
import com.datasphere.server.domain.storage.StorageProperties;

/**
 * Created by aladin on 2019. 5. 13..
 */
@RepositoryEventHandler(Metadata.class)
public class MetadataEventHandler {

  @Autowired
  MetaSourceService metaSourceService;

  @Autowired
  DataSourceService dataSourceService;

  @Autowired
  JdbcConnectionService jdbcConnectionService;

  @Autowired
  EngineProperties engineProperties;

  @Autowired(required = false)
  StorageProperties storageProperties;

  @HandleBeforeCreate
  public void handleBeforeCreate(Metadata metadata) {

    MetadataSource metadataSource = metadata.getSource();
    if (metadataSource.getType() == Metadata.SourceType.ENGINE) {

      // Check engine datasource info.
      if (StringUtils.isEmpty(metadataSource.getSourceId())) {
        throw new IllegalArgumentException("DataSource info. required.");
      }

      DataSource originalDataSource = (DataSource) metaSourceService
          .getSourcesBySourceId(metadataSource.getType(), metadataSource.getSourceId());

      if (originalDataSource == null) {
        throw new ResourceNotFoundException(metadataSource.getSourceId());
      }

      if (CollectionUtils.isNotEmpty(metadata.getColumns())) {
        // Check if the column information and the data in the actual data source match
        Map<String, Field> metaFieldMap = originalDataSource.getMetaFieldMap();
        for (MetadataColumn metadataColumn : metadata.getColumns()) {
          String physicalName = metadataColumn.getPhysicalName();
          String physicalType = metadataColumn.getPhysicalType();
          if (metaFieldMap.containsKey(physicalName)) {
            DataType dataType = metaFieldMap.get(physicalName).getType();
            if (!physicalType.equals(dataType.name())) {
              throw new IllegalArgumentException("Invalid physical column type. original type is '"
                                                     + dataType.name() + "' but '" + physicalName);
            }
          } else {
            throw new IllegalArgumentException("Invalid physical column name : " + physicalName);
          }

        }
      } else {
        // mapping column information
        if (CollectionUtils.isNotEmpty(originalDataSource.getFields())) {
          for (Field field : originalDataSource.getFields()) {
            metadata.addColumn(new MetadataColumn(field, metadata));
          }
        }
      }

    } else if (metadataSource.getType() == Metadata.SourceType.JDBC) {

      // Check jdbc connection info.
      if (StringUtils.isEmpty(metadataSource.getSourceId())) {
        throw new IllegalArgumentException("DataConnection info. required.");
      }

      DataConnection jdbcDataConnection = (DataConnection) metaSourceService
          .getSourcesBySourceId(metadataSource.getType(), metadataSource.getSourceId());
      String schema = metadataSource.getSchema();
      String tableName = metadataSource.getTable();

      JdbcAccessor jdbcDataAccessor = DataConnectionHelper.getAccessor(jdbcDataConnection);
      if (jdbcDataAccessor instanceof HiveDataAccessor) {
        HiveTableInformation hiveTableInformation
            = ((HiveDataAccessor) jdbcDataAccessor).showHiveTableDescription(jdbcDataConnection,
                                                                             jdbcDataConnection.getCatalog(),
                                                                             schema,
                                                                             tableName,
                                                                             false);

        // Set Column
        if (CollectionUtils.isNotEmpty(hiveTableInformation.getFields())) {
          for (int i = 0; i < hiveTableInformation.getFields().size(); i++) {
            Field field = hiveTableInformation.getFields().get(i);

            MetadataColumn metadataColumn = new MetadataColumn(field, metadata);
            metadataColumn.setSeq(i + 1L);

            metadata.addColumn(metadataColumn);
          }
        }

        // Set Detail information
        Map<String, Object> detailInfo = new HashMap<>();
        detailInfo.put("Detail Information", hiveTableInformation.getDetailInformation());
        detailInfo.put("Storage Information", hiveTableInformation.getStorageInformation());
        detailInfo.put("Partition Information", hiveTableInformation.getPartitionInformation());
        detailInfo.put("Partition Fields", hiveTableInformation.getPartitionFields());

        metadataSource.setSourceInfo(GlobalObjectMapper.writeValueAsString(detailInfo));
      } else {
        List<Map<String, Object>> columns = jdbcConnectionService.getTableColumnNames(jdbcDataConnection,
                                                                                       null,
                                                                                       schema,
                                                                                       tableName,
                                                                                       null,
                                                                                       null);

        if (CollectionUtils.isNotEmpty(columns)) {
          for (int i = 0; i < columns.size(); i++) {
            Map<String, Object> column = columns.get(i);

            MetadataColumn metadataColumn = new MetadataColumn();
            metadataColumn.setName((String) column.get("columnName"));
            metadataColumn.setPhysicalName((String) column.get("columnName"));
            metadataColumn.setPhysicalType((String) column.get("columnType"));
            metadataColumn.setDescription((String) column.get("columnComment"));
            metadataColumn.setSeq(i + 1L);

            //physicalType to LogicalType
            DataType physicalType = DataType.jdbcToFieldType(metadataColumn.getPhysicalType().toLowerCase());
            LogicalType logicalType = physicalType.toLogicalType();
            Field.FieldRole role = physicalType.toRole();
            metadataColumn.setType(logicalType);
            metadataColumn.setRole(role);
            metadataColumn.setMetadata(metadata);

            metadata.addColumn(metadataColumn);
          }
        }
      }
    } else if (metadataSource.getType() == Metadata.SourceType.STAGEDB) {

      String schema = metadataSource.getSchema();
      String tableName = metadataSource.getTable();

      if(storageProperties == null || storageProperties.getStagedb() == null) {
        throw new IllegalArgumentException("Staging database information required.");
      }
      StorageProperties.StageDBConnection stageDBConnection = storageProperties.getStagedb();

      DataConnection hiveConnection = new DataConnection();
      hiveConnection.setUrl(stageDBConnection.getUrl());
      hiveConnection.setHostname(stageDBConnection.getHostname());
      hiveConnection.setPort(stageDBConnection.getPort());
      hiveConnection.setUsername(stageDBConnection.getUsername());
      hiveConnection.setPassword(stageDBConnection.getPassword());
      hiveConnection.setImplementor("STAGE");

      JdbcAccessor jdbcDataAccessor = DataConnectionHelper.getAccessor(hiveConnection);
      HiveTableInformation hiveTableInformation
          = ((HiveDataAccessor) jdbcDataAccessor).showHiveTableDescription(hiveConnection,
                                                                           hiveConnection.getCatalog(),
                                                                           schema,
                                                                           tableName,
                                                                           false);

      // Set Column
      if (CollectionUtils.isNotEmpty(hiveTableInformation.getFields())) {
        for (int i = 0; i < hiveTableInformation.getFields().size(); i++) {
          Field field = hiveTableInformation.getFields().get(i);

          MetadataColumn metadataColumn = new MetadataColumn(field, metadata);
          metadataColumn.setSeq(i + 1L);

          metadata.addColumn(metadataColumn);
        }
      }

      // Set Detail information
      Map<String, Object> detailInfo = new HashMap<>();
      detailInfo.put("Detail Information", hiveTableInformation.getDetailInformation());
      detailInfo.put("Storage Information", hiveTableInformation.getStorageInformation());
      detailInfo.put("Partition Information", hiveTableInformation.getPartitionInformation());
      detailInfo.put("Partition Fields", hiveTableInformation.getPartitionFields());

      metadataSource.setSourceInfo(GlobalObjectMapper.writeValueAsString(detailInfo));
    }
  }

  @HandleAfterCreate
  public void handleAfterCreate(Metadata metadata) {
  }

  @HandleAfterSave
  public void handleAfterSave(Metadata metadata) {

    // Sync with datasource
    if (metadata.getSourceType() == Metadata.SourceType.ENGINE) {
      dataSourceService.updateFromMetadata(metadata, false);
    }
  }

  @HandleBeforeDelete
  public void handleBeforeDelete(Metadata metadata) {
  }
}
