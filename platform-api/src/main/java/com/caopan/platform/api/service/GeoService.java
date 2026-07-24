package com.caopan.platform.api.service;

import com.caopan.platform.api.vo.CountryVO;
import com.caopan.platform.api.vo.RegionSearchVO;
import com.caopan.platform.api.vo.RegionTreeVO;
import com.caopan.platform.api.vo.RegionVO;

import java.util.List;

/**
 * 行政区划服务接口。定义 geo 模块对外提供的所有查询能力。
 * 由 {@code platform-geo-service} 模块的 {@code GeoServiceImpl} 实现；
 * HTTP SDK 见 {@code GeoHttpClient}。
 */
public interface GeoService {

    /**
     * 查询启用国家列表，支持语言与关键词过滤。
     *
     * @param lang    语言偏好（local/en/zh，可空）
     * @param keyword 关键词，可空
     * @return 国家列表
     */
    List<CountryVO> listCountries(String lang, String keyword);

    /**
     * 按父节点 ID 查询直属子行政区划列表。
     *
     * @param parentId 父节点 ID
     * @param lang     语言偏好（local/en/zh，可空）
     * @return 子级区划列表
     */
    List<RegionVO> listChildren(Long parentId, String lang);

    /**
     * 按国家编码（及可选根节点、深度）组装行政区划树。
     *
     * @param countryCode 国家 ISO2 编码
     * @param rootId      根节点 ID，可空（空则从国家级开始）
     * @param depth       深度限制 1~5，可空
     * @param lang        语言偏好（local/en/zh，可空）
     * @return 树根节点（含嵌套 children）
     */
    RegionTreeVO getTree(String countryCode, Long rootId, Integer depth, String lang);

    /**
     * 按区划 ID 回显从国家到当前节点的有序祖先链。
     *
     * @param id   区划 ID
     * @param lang 语言偏好（local/en/zh，可空）
     * @return 祖先链（国家→…→当前）
     */
    List<RegionVO> getPath(Long id, String lang);

    /**
     * 按关键词搜索行政区划，返回命中节点及全路径名称。
     *
     * @param keyword     关键词
     * @param countryCode 国家 ISO2 编码（必填）
     * @param level       层级过滤，可空
     * @param limit       返回条数上限
     * @param lang        语言偏好（local/en/zh，可空）
     * @return 搜索结果列表
     */
    List<RegionSearchVO> search(String keyword, String countryCode, Integer level, Integer limit, String lang);
}
