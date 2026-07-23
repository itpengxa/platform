package com.caopan.platform.geo.service;

import com.caopan.platform.geo.vo.CountryVO;
import com.caopan.platform.geo.vo.RegionSearchVO;
import com.caopan.platform.geo.vo.RegionTreeVO;
import com.caopan.platform.geo.vo.RegionVO;

import java.util.List;

/**
 * 2026-07-23 GEO-001 行政区划查询服务
 */
public interface GeoService {

    List<CountryVO> listCountries(String lang, String keyword);

    List<RegionVO> listChildren(Long parentId, String lang);

    RegionTreeVO getTree(String countryCode, Long rootId, Integer depth, String lang);

    List<RegionVO> getPath(Long id, String lang);

    List<RegionSearchVO> search(String keyword, String countryCode, Integer level, Integer limit, String lang);
}
