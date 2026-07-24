package com.caopan.platform.api.service;

import com.caopan.platform.api.vo.CountryVO;
import com.caopan.platform.api.vo.RegionSearchVO;
import com.caopan.platform.api.vo.RegionTreeVO;
import com.caopan.platform.api.vo.RegionVO;

import java.util.List;

/**
 * 行政区划服务接口。定义 geo 模块对外提供的所有查询能力。
 * 由 platform-geo-service 模块的 GeoServiceImpl 实现。
 */
public interface GeoService {

    List<CountryVO> listCountries(String lang, String keyword);

    List<RegionVO> listChildren(Long parentId, String lang);

    RegionTreeVO getTree(String countryCode, Long rootId, Integer depth, String lang);

    List<RegionVO> getPath(Long id, String lang);

    List<RegionSearchVO> search(String keyword, String countryCode, Integer level, Integer limit, String lang);
}
