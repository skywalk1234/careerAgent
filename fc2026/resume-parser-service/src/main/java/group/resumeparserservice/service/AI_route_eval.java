package group.resumeparserservice.service;/* I love coding */


import group.resumeparserservice.prompts.DeepEvalPrompt;
import group.resumeparserservice.prompts.JobAnalyzePrompt;
import group.resumeparserservice.prompts.RealTimeEvalPrompt;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AI_route_eval {
    private final ChatClient chatClient;
    public String realTimeEvaluate(String node_json){
        log.info("开始使用AI评估路径，长度: {} 字符", node_json.length());
        String prompt = RealTimeEvalPrompt.buildQuickRealEvalPrompt(node_json);
        String res_json = chatClient
                        .prompt(prompt) // 传入user提示词
                        .options(
                                ChatOptions.builder()
                                        .model("qwen3-0.6b")  // 动态设置模型
                                        .build()
                        )
                        .call() // 同步请求，会等待AI全部输出完才返回结果
                        .content(); //返回响应内容
        return res_json;

    }

    public String deepEvaluate(String node_json){
        log.info("开始使用AI深度评估路径，长度: {} 字符", node_json.length());
        String prompt = DeepEvalPrompt.getPrompt(node_json);
        String res_json = chatClient
                        .prompt(prompt) // 传入user提示词
                        .options(
                                ChatOptions.builder()
                                        .model("qwen-turbo")  // 动态设置模型
                                        .build()
                        )
                        .call() // 同步请求，会等待AI全部输出完才返回结果
                        .content(); //返回响应内容


        return res_json;
    }

    public String analyzeJobs(String request_json) {
        log.info("开始使用AI进行人岗多维度详细分析，长度: {} 字符", request_json.length());
        String prompt = JobAnalyzePrompt.getPrompt(request_json);
        String res_json = chatClient
                        .prompt(prompt)
                        .options(
                                ChatOptions.builder()
                                        .model("qwen3-0.6b")
                                        .build()
                        )
                        .call()
                        .content();
        return res_json;
    }
}
