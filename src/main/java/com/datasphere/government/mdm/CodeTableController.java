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

package com.datasphere.government.mdm;

import com.google.common.collect.Maps;

import com.querydsl.core.types.Predicate;

import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.projection.ProjectionFactory;
import org.springframework.data.rest.webmvc.PersistentEntityResourceAssembler;
import org.springframework.data.rest.webmvc.RepositoryRestController;
import org.springframework.data.web.PagedResourcesAssembler;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;
import java.util.Map;

import com.datasphere.server.common.exception.ResourceNotFoundException;
import com.datasphere.server.domain.CollectionPatch;
import com.datasphere.server.util.ProjectionUtils;

@RepositoryRestController
public class CodeTableController {

  private static Logger LOGGER = LoggerFactory.getLogger(CodeTableController.class);

  @Autowired
  CodeTableRepository codeTableRepository;

  @Autowired
  ColumnDictionaryRepository columnDictionaryRepository;

  @Autowired
  PagedResourcesAssembler pagedResourcesAssembler;

  @Autowired
  ProjectionFactory projectionFactory;

  ColumnDictionaryProjections columnDictionaryProjections = new ColumnDictionaryProjections();

  public CodeTableController() {
  }

  /**
   * Column Dictionary 목록을 조회합니다.
   */
  @RequestMapping(value = "/codetables", method = RequestMethod.GET)
  public ResponseEntity<?> findCodeTables(
      @RequestParam(value = "nameContains", required = false) String nameContains,
      @RequestParam(value = "searchDateBy", required = false) String searchDateBy,
      @RequestParam(value = "from", required = false)
        @DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) DateTime from,
      @RequestParam(value = "to", required = false)
        @DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) DateTime to,
      Pageable pageable, PersistentEntityResourceAssembler resourceAssembler) {

    // Get Predicate
    Predicate searchPredicated = CodeTablePredicate.searchList(nameContains, searchDateBy, from, to);

    // 기본 정렬 조건 셋팅
    if (pageable.getSort() == null || !pageable.getSort().iterator().hasNext()) {
      pageable = new PageRequest(pageable.getPageNumber(), pageable.getPageSize(),
                                 new Sort(Sort.Direction.ASC, "name"));
    }

    // Find by predicated
    Page<CodeTable> codeTables = codeTableRepository.findAll(searchPredicated, pageable);

    return ResponseEntity.ok(this.pagedResourcesAssembler.toResource(codeTables, resourceAssembler));
  }

  @RequestMapping(path = "/codetables/name/{value}/duplicated", method = RequestMethod.GET)
  public ResponseEntity<?> checkDuplicatedValue(@PathVariable("value") String value) {

    Map<String, Boolean> duplicated = Maps.newHashMap();
    if (codeTableRepository.exists(CodeTablePredicate.searchDuplicatedName(value))) {
      duplicated.put("duplicated", true);
    } else {
      duplicated.put("duplicated", false);
    }

    return ResponseEntity.ok(duplicated);

  }

  /**
   * 코드 테이블과 연결된 Column Dictionary 정보를 가져옵니다
   */
  @RequestMapping(value = "/codetables/{tableId}/dictionaries", method = RequestMethod.GET)
  public ResponseEntity<?> findDictionariesCodeTable(@PathVariable("tableId") String tableId,
                                                     @RequestParam(value = "projection", required = false, defaultValue = "default") String projection,
                                                     Pageable pageable) {

    CodeTable codeTable = codeTableRepository.findById(tableId).get();
    if (codeTable == null) {
      throw new ResourceNotFoundException(tableId);
    }

    // Get Predicate
    Predicate searchPredicated = ColumnDictionaryPredicate.searchListInCodeTable(codeTable, null, null, null, null, null);

    if (pageable.getSort() == null || !pageable.getSort().iterator().hasNext()) {
      pageable = new PageRequest(pageable.getPageNumber(), pageable.getPageSize(),
                                 new Sort(Sort.Direction.ASC, "logicalName", "name"));
    }

    // Find by predicated
    Page<ColumnDictionary> dictionaries = columnDictionaryRepository.findAll(searchPredicated, pageable);

    return ResponseEntity.ok(this.pagedResourcesAssembler.toResource(
        ProjectionUtils.toPageResource(projectionFactory,
                                       columnDictionaryProjections.getProjectionByName(projection),
                                       dictionaries))
    );
  }

  /**
   * 코드 테이블내 코드 정보를 수정합니다
   *
   * @param tableId
   * @param patches
   * @return
   */
  @RequestMapping(path = "/codetables/{tableId}/codes", method = {RequestMethod.PATCH, RequestMethod.PUT})
  public @ResponseBody
  ResponseEntity<?> patchCodesInCodeTable(@PathVariable("tableId") String tableId,
                                          @RequestBody List<CollectionPatch> patches) {

    CodeTable codeTable = codeTableRepository.findById(tableId).get();
    if (codeTable == null) {
      throw new ResourceNotFoundException(tableId);
    }

    Map<Long, CodeValuePair> codeMap = codeTable.getCodeMap();
    for (CollectionPatch patch : patches) {
      Long codeId = patch.getLongValue("id");
      switch (patch.getOp()) {
        case ADD:
          codeTable.addCode(new CodeValuePair(patch));
          LOGGER.debug("Add code in code table({})", tableId);
          break;
        case REPLACE:
          if (codeMap.containsKey(codeId)) {
            CodeValuePair code = codeMap.get(codeId);
            code.updateCodeValuePair(patch);
            LOGGER.debug("Updated code in code table({}) : {}", tableId, codeId);
          }
          break;
        case REMOVE:
          if (codeMap.containsKey(codeId)) {
            codeTable.removeCode(codeMap.get(codeId));
            LOGGER.debug("Deleted code in code table({}) : {}", tableId, codeId);
          }
          break;
        default:
          break;
      }
    }

    codeTableRepository.save(codeTable);

    return ResponseEntity.noContent().build();
  }
}
