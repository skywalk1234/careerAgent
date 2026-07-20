package group.careerservice.controller;

import group.careerservice.domain.po.CityStatsPO;
import group.careerservice.domain.po.ProvinceStatsPO;
import group.careerservice.service.JobMap.JobMapService;
import group.common.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/analytics/job-map")
@RequiredArgsConstructor
@Slf4j
public class MapController {

    private final JobMapService jobMapService;

    @GetMapping("/provinces")
    public Result getProvinceStats(
            @RequestParam(required = false) String jobFamily,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String cityTier,
            @RequestParam(required = false) String keyword) {

        log.info("获取岗位地图省级聚合数据, jobFamily: {}, level: {}, cityTier: {}, keyword: {}",
                jobFamily, level, cityTier, keyword);

        List<ProvinceStatsPO> provinceStatsList = jobMapService.getProvinceStats(jobFamily, level, cityTier, keyword);

        List<Map<String, Object>> list = provinceStatsList.stream().map(po -> {
            Map<String, Object> map = new HashMap<>();
            map.put("provinceName", po.getProvinceName());
            map.put("provinceAdcode", po.getProvinceAdcode());
            map.put("jobCount", po.getJobCount());
            map.put("jdCount", po.getJdCount());
            map.put("cityCount", po.getCityCount());
            map.put("avgSalaryMonthly", po.getAvgSalaryMonthly());
            map.put("topJobs", po.getTopJobs());
            map.put("tierDistribution", po.getTierDistribution());
            return map;
        }).collect(Collectors.toList());

        Map<String, Object> data = new HashMap<>();
        data.put("list", list);
        data.put("updatedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME) + "Z");

        return Result.success(data, "岗位地图省级聚合获取成功");
    }

    @GetMapping("/cities")
    public Result getCityStats(
            @RequestParam Integer provinceAdcode,
            @RequestParam(required = false) String jobFamily,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String cityTier,
            @RequestParam(required = false) String keyword) {

        log.info("获取岗位地图城市聚合数据, provinceAdcode: {}, jobFamily: {}, level: {}, cityTier: {}, keyword: {}",
                provinceAdcode, jobFamily, level, cityTier, keyword);

        if (provinceAdcode == null) {
            return Result.error(400, "provinceAdcode不能为空");
        }

        List<CityStatsPO> cityStatsList = jobMapService.getCityStats(provinceAdcode, jobFamily, level, cityTier, keyword);

        String provinceName = cityStatsList.isEmpty() ? "" : cityStatsList.get(0).getProvinceName();

        List<Map<String, Object>> list = cityStatsList.stream().map(po -> {
            Map<String, Object> map = new HashMap<>();
            map.put("cityName", po.getCityName());
            map.put("cityAdcode", po.getCityAdcode());
            map.put("provinceName", po.getProvinceName());
            map.put("provinceAdcode", po.getProvinceAdcode());
            map.put("jobCount", po.getJobCount());
            map.put("jdCount", po.getJdCount());
            map.put("avgSalaryMonthly", po.getAvgSalaryMonthly());
            map.put("tier", po.getTier());
            map.put("topJobs", po.getTopJobs());
            return map;
        }).collect(Collectors.toList());

        Map<String, Object> data = new HashMap<>();
        data.put("provinceAdcode", provinceAdcode);
        data.put("provinceName", provinceName);
        data.put("list", list);
        data.put("updatedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME) + "Z");

        return Result.success(data, "岗位地图城市聚合获取成功");
    }

    @GetMapping("/popup")
    public Result getPopupData(
            @RequestParam String level,
            @RequestParam Integer adcode,
            @RequestParam(required = false) String jobFamily,
            @RequestParam(required = false) String jobLevel,
            @RequestParam(required = false) String cityTier,
            @RequestParam(required = false) String keyword) {

        log.info("获取岗位地图弹窗数据, level: {}, adcode: {}, jobFamily: {}, jobLevel: {}, cityTier: {}, keyword: {}",
                level, adcode, jobFamily, jobLevel, cityTier, keyword);

        if (!StringUtils.hasText(level)) {
            return Result.error(400, "level不能为空");
        }

        if (adcode == null) {
            return Result.error(400, "adcode不能为空");
        }

        if (!"province".equalsIgnoreCase(level) && !"city".equalsIgnoreCase(level)) {
            return Result.error(400, "level参数必须是 province 或 city");
        }

        Map<String, Object> data = jobMapService.getPopupData(level, adcode, jobFamily, jobLevel, cityTier, keyword);

        if (data.isEmpty()) {
            return Result.error(404, "未找到对应的数据");
        }

        String msg = "city".equalsIgnoreCase(level) ? "城市弹窗数据获取成功" : "省份弹窗数据获取成功";
        return Result.success(data, msg);
    }
}
