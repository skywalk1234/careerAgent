package group.careerservice.client;/* I love coding */

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 直连 Python ai-service (默认 8086，可用 ai-service.url 覆盖)。
 * 方法签名与 AiClient 的 /jobs/recommend* 完全一致（String body → String 返回），
 * 保证线上 body（JSON 字符串字面量）编码与 String 返回解码行为不变。
 *
 * url= 直连，绕过 Nacos 服务发现（Python 服务不注册到 Nacos）。
 */
@FeignClient(name = "ai-service-py", url = "${ai-service.url:http://127.0.0.1:8086}")
public interface JobRecommendClient {

    //    推荐岗位大类
    @PostMapping("/jobs/recommend")
    String recommendJobs(@RequestBody String request_json);

    //  推荐具体岗位
    @PostMapping("/jobs/recommend/specific")
    String recommendSpecificJobs(@RequestBody String request_json);
}
