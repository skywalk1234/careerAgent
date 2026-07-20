package group.resumeparserservice.controller;/* I love coding */

import group.dto.JobNodes;
import group.resumeparserservice.tools.JobNodeInitializer;
import group.resumeparserservice.tools.JobSpecificInitializer;
import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping
@Slf4j
public class VectorStoreController {
//    private final PgVectorStore vectorStore;
    private final JobNodeInitializer jobNodeInitializer;
    private final JobSpecificInitializer jobSpecificInitializer;

    @GetMapping("/vector_store/create")
    public String createVectorStore() throws Exception {
        log.info("开始创建岗位大类向量库");
        JobNodes node = new JobNodes();
        jobNodeInitializer.run();
        return "";
    }

    @GetMapping("/vector_store/job_specific/create")
    public String createJobSpecificVectorStore() throws Exception {
        log.info("开始创建具体岗位向量库");
        jobSpecificInitializer.run();
        return "ok";
    }
}
