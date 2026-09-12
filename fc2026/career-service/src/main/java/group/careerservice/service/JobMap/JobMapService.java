package group.careerservice.service.JobMap;

import group.careerservice.domain.po.CityStatsPO;
import group.careerservice.domain.po.ProvinceStatsPO;

import java.util.List;
import java.util.Map;

public interface JobMapService {

    List<ProvinceStatsPO> getProvinceStats(String jobFamily, String level, String cityTier, String keyword);

    List<CityStatsPO> getCityStats(Integer provinceAdcode, String jobFamily, String level, String cityTier, String keyword);

    Map<String, Object> getPopupData(String level, Integer adcode, String jobFamily, String jobLevel, String cityTier, String keyword);
}
