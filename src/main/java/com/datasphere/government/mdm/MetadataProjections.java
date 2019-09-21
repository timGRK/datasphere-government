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

import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.rest.core.config.Projection;

import java.util.List;

import com.datasphere.server.common.BaseProjections;
import com.datasphere.server.domain.user.UserProfile;

public class MetadataProjections extends BaseProjections {

  @Projection(types = Metadata.class, name = "default")
  public interface DefaultProjection {

    String getId();

    String getName();

    String getDescription();
  }

  @Projection(types = Metadata.class, name = "forListView")
  public interface ForListViewProjection {

    String getId();

    String getName();

    String getDescription();

    Metadata.SourceType getSourceType();

    @Value("#{@metadataPopularityService.getPopularityValue(target.id)}")
    Double getPopularity();

    @Value("#{@tagService.findByTagsInDomainItem(T(com.datasphere.server.domain.tag.Tag$Scope).DOMAIN, T(com.datasphere.server.common.entity.DomainType).METADATA, target.id, 'default')}")
    Object getTags();

    @Value("#{@cachedUserService.findUserProfile(target.createdBy)}")
    UserProfile getCreatedBy();

    @Value("#{@cachedUserService.findUserProfile(target.modifiedBy)}")
    UserProfile getModifiedBy();

    DateTime getCreatedTime();

    DateTime getModifiedTime();
  }

  @Projection(types = Metadata.class, name = "forDetailView")
  public interface ForDetailViewProjection {

    String getId();

    String getName();

    String getDescription();

    Metadata.SourceType getSourceType();

    List<MetadataColumn> getColumns();

    @Value("#{@metadataPopularityService.getPopularityValue(target.id)}")
    Double getPopularity();

    @Value("#{T(com.datasphere.server.util.ProjectionUtils).toResource(@projectionFactory, T(com.datasphere.server.domain.mdm.source.MetadataSourceProjections$ForDetailViewProjection), target.source)}")
    Object getSource();

    @Value("#{T(com.datasphere.server.util.ProjectionUtils).toListResource(@projectionFactory, T(com.datasphere.server.domain.mdm.catalog.CatalogProjections$HierarchyViewProjection), target.catalogs)}")
    Object getCatalogs();

    @Value("#{@tagService.findByTagsInDomainItem(T(com.datasphere.server.domain.tag.Tag$Scope).DOMAIN, T(com.datasphere.server.common.entity.DomainType).METADATA, target.id, 'default')}")
    Object getTags();

    @Value("#{@cachedUserService.findUserProfile(target.createdBy)}")
    UserProfile getCreatedBy();

    @Value("#{@cachedUserService.findUserProfile(target.modifiedBy)}")
    UserProfile getModifiedBy();

    DateTime getCreatedTime();

    DateTime getModifiedTime();
  }

  @Projection(types = Metadata.class, name = "forItemListView")
  public interface ForItemListViewProjection {

    String getId();

    String getName();

    String getDescription();

    @Value("#{target.source.sourceId}")
    String getSourceId();

    @Value("#{target.source.schema}")
    String getSchema();

    @Value("#{target.source.table}")
    String getTable();
  }


  @Projection(types = Metadata.class, name = "forItemView")
  public interface ForItemViewProjection {

    String getId();

    String getName();

    String getDescription();

    @Value("#{target.source.sourceId}")
    String getSourceId();

    @Value("#{target.source.schema}")
    String getSchema();

    @Value("#{target.source.table}")
    String getTable();

    @Value("#{T(com.datasphere.server.util.ProjectionUtils).toListResource(@projectionFactory, T(com.datasphere.server.domain.mdm.MetadataColumnProjections$ForListViewProjection), target.columns)}")
    Object getColumns();
  }

}
