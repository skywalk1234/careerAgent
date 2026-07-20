package group.careerservice.service.JobMap;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import group.careerservice.domain.po.CityStatsPO;
import group.careerservice.domain.po.ProvinceStatsPO;
import group.careerservice.mapper.CityStatsMapper;
import group.careerservice.mapper.ProvinceStatsMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class JobMapServiceImpl implements JobMapService {

    private final ProvinceStatsMapper provinceStatsMapper;
    private final CityStatsMapper cityStatsMapper;

    @Override
    public List<ProvinceStatsPO> getProvinceStats(String jobFamily, String level, String cityTier, String keyword) {
        QueryWrapper<ProvinceStatsPO> queryWrapper = new QueryWrapper<>();

        if (StringUtils.hasText(jobFamily)) {
            queryWrapper.apply("JSON_CONTAINS(top_jobs, JSON_OBJECT('name', {0}))", jobFamily);
        }

        if (StringUtils.hasText(level)) {
            queryWrapper.apply("JSON_CONTAINS(top_jobs, JSON_OBJECT('name', {0}))", level);
        }

        if (StringUtils.hasText(cityTier)) {
            String tierName = convertTierToChinese(cityTier);
            queryWrapper.apply("JSON_CONTAINS(tier_distribution, JSON_OBJECT('tier', {0}))", tierName);
        }

        if (StringUtils.hasText(keyword)) {
            queryWrapper.and(qw -> qw.like("province_name", keyword)
                    .or()
                    .apply("JSON_CONTAINS(top_jobs, JSON_OBJECT('name', {0}))", keyword));
        }

        return provinceStatsMapper.selectList(queryWrapper);
    }

    @Override
    public List<CityStatsPO> getCityStats(Integer provinceAdcode, String jobFamily, String level, String cityTier, String keyword) {
        QueryWrapper<CityStatsPO> queryWrapper = new QueryWrapper<>();

        queryWrapper.eq("province_adcode", provinceAdcode);

        if (StringUtils.hasText(jobFamily)) {
            queryWrapper.apply("JSON_CONTAINS(top_jobs, JSON_OBJECT('name', {0}))", jobFamily);
        }

        if (StringUtils.hasText(level)) {
            queryWrapper.apply("JSON_CONTAINS(top_jobs, JSON_OBJECT('name', {0}))", level);
        }

        if (StringUtils.hasText(cityTier)) {
            String tierName = convertTierToChinese(cityTier);
            queryWrapper.eq("tier", tierName);
        }

        if (StringUtils.hasText(keyword)) {
            queryWrapper.and(qw -> qw.like("city_name", keyword)
                    .or()
                    .like("province_name", keyword)
                    .or()
                    .apply("JSON_CONTAINS(top_jobs, JSON_OBJECT('name', {0}))", keyword));
        }

        return cityStatsMapper.selectList(queryWrapper);
    }

    @Override
    public Map<String, Object> getPopupData(String level, Integer adcode, String jobFamily, String jobLevel, String cityTier, String keyword) {
        Map<String, Object> result = new HashMap<>();

        if ("province".equalsIgnoreCase(level)) {
            QueryWrapper<ProvinceStatsPO> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("province_adcode", adcode);

            if (StringUtils.hasText(jobFamily)) {
                queryWrapper.apply("JSON_CONTAINS(top_jobs, JSON_OBJECT('name', {0}))", jobFamily);
            }

            if (StringUtils.hasText(jobLevel)) {
                queryWrapper.apply("JSON_CONTAINS(top_jobs, JSON_OBJECT('name', {0}))", jobLevel);
            }

            if (StringUtils.hasText(cityTier)) {
                String tierName = convertTierToChinese(cityTier);
                queryWrapper.apply("JSON_CONTAINS(tier_distribution, JSON_OBJECT('tier', {0}))", tierName);
            }

            if (StringUtils.hasText(keyword)) {
                queryWrapper.and(qw -> qw.like("province_name", keyword)
                        .or()
                        .apply("JSON_CONTAINS(top_jobs, JSON_OBJECT('name', {0}))", keyword));
            }

            ProvinceStatsPO provinceStats = provinceStatsMapper.selectOne(queryWrapper);
            if (provinceStats != null) {
                result.put("level", "province");
                result.put("provinceName", provinceStats.getProvinceName());
                result.put("provinceAdcode", provinceStats.getProvinceAdcode());
                result.put("jobCount", provinceStats.getJobCount());
                result.put("jdCount", provinceStats.getJdCount());
                result.put("cityCount", provinceStats.getCityCount());
                result.put("avgSalaryMonthly", provinceStats.getAvgSalaryMonthly());
                result.put("topJobs", provinceStats.getTopJobs());
                result.put("tierDistribution", provinceStats.getTierDistribution());
            }
        } else if ("city".equalsIgnoreCase(level)) {
            QueryWrapper<CityStatsPO> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("city_adcode", adcode);

            if (StringUtils.hasText(jobFamily)) {
                queryWrapper.apply("JSON_CONTAINS(top_jobs, JSON_OBJECT('name', {0}))", jobFamily);
            }

            if (StringUtils.hasText(jobLevel)) {
                queryWrapper.apply("JSON_CONTAINS(top_jobs, JSON_OBJECT('name', {0}))", jobLevel);
            }

            if (StringUtils.hasText(cityTier)) {
                String tierName = convertTierToChinese(cityTier);
                queryWrapper.eq("tier", tierName);
            }

            if (StringUtils.hasText(keyword)) {
                queryWrapper.and(qw -> qw.like("city_name", keyword)
                        .or()
                        .like("province_name", keyword)
                        .or()
                        .apply("JSON_CONTAINS(top_jobs, JSON_OBJECT('name', {0}))", keyword));
            }

            CityStatsPO cityStats = cityStatsMapper.selectOne(queryWrapper);
            if (cityStats != null) {
                result.put("level", "city");
                result.put("cityName", cityStats.getCityName());
                result.put("cityAdcode", cityStats.getCityAdcode());
                result.put("provinceName", cityStats.getProvinceName());
                result.put("provinceAdcode", cityStats.getProvinceAdcode());
                result.put("jobCount", cityStats.getJobCount());
                result.put("jdCount", cityStats.getJdCount());
                result.put("avgSalaryMonthly", cityStats.getAvgSalaryMonthly());
                result.put("tier", cityStats.getTier());
                result.put("topJobs", cityStats.getTopJobs());
            }
        }

        return result;
    }

    private String convertTierToChinese(String cityTier) {
        switch (cityTier) {
            case "tier1":
                return "一线";
            case "tier2":
                return "二线";
            case "tier3":
                return "三线及其他";
            default:
                return cityTier;
        }
    }
}
