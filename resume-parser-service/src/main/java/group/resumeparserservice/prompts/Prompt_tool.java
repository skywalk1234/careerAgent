package group.resumeparserservice.prompts;/* I love coding */

public class Prompt_tool {
    public static String buildMarkdownPrompt(String resumeText) {
        return """
            请将以下简历的原始文本整理成结构清晰、可读性强的 Markdown 格式。

            要求：
            1. 使用合适的 Markdown 语法：标题（## / ###）、有序/无序列表（- / 1.）、加粗（**）、换行等
            2. 对简历进行合理的分区，例如：基本信息、教育背景、工作/实习经历、项目经历、技能、证书、获奖情况、自我评价等
            3. 保留原始文本中的所有内容和细节，不得增删、改写、概括任何事实信息，仅调整排版与层级
            4. 保持原有的语句表达，不要润色措辞或修改语气
            5. 如果原始文本本身没有分区结构，请根据内容语义推断合理分区
            6. 只返回整理后的 Markdown 文本，不要包含任何解释性文字，不要使用代码块包裹

            以下是需要整理的简历原始文本：

            """
                + resumeText + "\n";
    }
    public static String buildScorePrompt(String resumeText) {
        return """
        你是一位专业的简历评估专家。请严格分析以下简历内容，按照12个维度进行打分，并返回指定格式的JSON结果。
        
        **评分规则**：
        1. **分数范围**：所有维度的评分使用1-100分的整数
        2. **评分基准**：
           - 90-100分：该方面表现卓越，远超同龄人平均水平
           - 80-89分：该方面表现优秀，明显优于同龄人
           - 70-79分：该方面表现良好，达到岗位基本要求
           - 60-69分：该方面表现一般，有提升空间
           - 1-59分：该方面表现不足，需要重点加强
        3. **评分依据**：必须基于简历中的具体内容进行客观评估，不能主观臆断
        
        **12个维度定义**：
        1. **professionalSkill（专业技能）**：技术栈与岗位匹配度、项目技术难度与深度
        2. **certificate（证书能力）**：岗位相关证书/资格完整度（如软考、云证书等）
        3. **innovation（创新能力）**：创新项目、竞赛创意、改进方案与成果
        4. **internalMotivation（内驱动力）**：目标自驱、长期投入、主动学习与持续改进能力
        5. **learning（学习能力）**：课程/项目迭代速度、自主学习证据、跨域学习记录
        6. **stressTolerance（抗压能力）**：高压场景稳定输出、冲突处理与复盘能力
        7. **communication（沟通能力）**：跨团队协作、表达清晰度、协作反馈证据
        8. **internship（实习能力）**：实习质量、岗位相关度、产出可量化程度
        9. **language（语言能力）**：英语/小语种能力（CET、雅思等）与跨语言沟通实践
        10. **leadership（领导能力）**：带队经历、组织协调、任务分配与推动落地能力
        11. **adaptability（适应能力）**：新环境/新技术适应速度与迁移学习能力
        12. **execution（执行能力）**：计划拆解、里程碑达成率、项目交付质量
        
        **输出要求**：
        1. **必须严格返回以下JSON格式**，不允许有任何额外文本、注释或解释
        2. `bonusByDimension` 字段请原样返回我提供的值，不要修改
        3. `evidence` 字段：为每个打分的维度提供1-3条具体的证据，必须是简历中明确提到的内容
        4. `improvementSuggestions` 字段：针对分数较低（<70分）或缺少证据的维度，提供具体可操作的建议
        
        **待评估简历内容**：
        %s
        
        **bonusByDimension 固定值（请原样返回）**：
        {
          "professionalSkill": 2,
          "certificate": 0,
          "innovation": 2,
          "internalMotivation": 1,
          "learning": 0,
          "stressTolerance": 0,
          "communication": 1,
          "internship": 0,
          "language": 0,
          "leadership": 0,
          "adaptability": 0,
          "execution": 3
        }
        
        **请返回严格的JSON格式**：
        {
          "scores": {
            "completenessScore": [根据简历信息完整度打分, 1-100],
            "competitivenessScore": [根据简历整体竞争力打分, 1-100],
            "abilityScores": {
              "professionalSkill": [分数],
              "certificate": [分数],
              "innovation": [分数],
              "internalMotivation": [分数],
              "learning": [分数],
              "stressTolerance": [分数],
              "communication": [分数],
              "internship": [分数],
              "language": [分数],
              "leadership": [分数],
              "adaptability": [分数],
              "execution": [分数]
            },
            "bonusByDimension": [请原样返回上面的固定值]
          },
          "evidence": {
            "professionalSkill": ["具体证据1", "具体证据2"],
            "certificate": ["具体证据"],
            ... [其他有证据的维度]
          },
          "improvementSuggestions": [
            {
              "dimension": "维度名称",
              "priority": "high/medium/low",
              "advice": "具体建议"
            }
          ]
        }
        
        记住：只返回JSON，不要有任何其他内容！
        """.formatted(resumeText);
    }
}