package com.caopan.platform.api.service;

import com.caopan.platform.api.vo.CountryVO;
import com.caopan.platform.api.vo.RegionSearchVO;
import com.caopan.platform.api.vo.RegionTreeVO;
import com.caopan.platform.api.vo.RegionVO;

import java.util.List;

/**
 * 2026-07-24 GEO-001 行政区划查询契约（JDK 8 可引用）
 */
public interface GeoService {

    List<CountryVO> listCountries(String lang, String keyword);

    List<RegionVO> listChildren(Long parentId, String lang);

    RegionTreeVO getTree(String countryCode, Long rootId, Integer depth, String lang);

    List<RegionVO> getPath(Long id, String lang);

    List<RegionSearchVO> search(String keyword, String countryCode, Integer level, Integer limit, String lang);
}
