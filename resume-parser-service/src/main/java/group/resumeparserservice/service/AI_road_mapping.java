package group.resumeparserservice.service;/* I love coding */

import group.resumeparserservice.prompts.RoadMappingPrompt;
import group.resumeparserservice.prompts.RoadMappingPolishPrompt;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Service;
// ai生涯规划
@Service
@Slf4j
@RequiredArgsConstructor
public class AI_road_mapping {
    private final ChatClient chatClient;
    public String create_road_mapping(String node_json){
        log.info("开始使用AI生成生涯规划报告，长度: {} 字符", node_json.length());
        String prompt = RoadMappingPrompt.getPrompt(node_json);
        String res_json = chatClient
                .prompt(prompt) // 传入user提示词
                .options(
                        ChatOptions.builder()
                                .model("qwen-plus-2025-12-01")  // 动态设置模型
                                .build()
                )
                .call() // 同步请求，会等待AI全部输出完才返回结果
                .content(); //返回响应内容
        return res_json;

    }

    public String polish_road_mapping(String request_json) {
        log.info("开始使用AI润色生涯规划报告，长度: {} 字符", request_json.length());
        String prompt = RoadMappingPolishPrompt.getPrompt(request_json);
        String res_json = chatClient
                .prompt(prompt)
                .options(
                        ChatOptions.builder()
                                .model("qwen-plus-2025-12-01")
                                .build()
                )
                .call()
                .content();
        return res_json;
    }


}
