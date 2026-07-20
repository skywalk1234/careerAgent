package group.resumeparserservice.prompts;/* I love coding */

public class Prompt_tool {
    public static String buildExtractionPrompt(String resumeText) {
        return """
            请仔细分析以下简历文本，从中提取结构化的信息，并以JSON格式返回。
            
            返回的JSON必须严格匹配以下Java类结构：
            {
              "basicInfo": {
                "name": "姓名",
                "gender": "性别，填写 male/female",
                "birthday": "出生日期，格式为YYYY-MM-DD",
                "phone": "手机号",
                "email": "邮箱",
                "city": "城市",
                "jobIntention": ["意向职位数组"]
              },
              "education": [
                {
                  "school": "学校名称",
                  "major": "专业",
                  "degree": "学历（本科/硕士等）",
                  "startDate": "开始日期，格式为YYYY-MM",
                  "endDate": "结束日期，格式为YYYY-MM",
                  "gpa": "绩点"
                }
              ],
              "workExperience": [
                {
                  "company": "公司名称",
                  "role": "职位",
                  "startDate": "开始日期，格式为YYYY-MM",
                  "endDate": "结束日期，格式为YYYY-MM",
                  "description": "工作描述"
                }
              ],
              "skills": ["技能1", "技能2"],
              "certificates": [
                {
                  "name": "证书名称",
                  "date": "获得日期，格式为YYYY-MM",
                  "issuer": "颁发机构"
                }
              ],
              "organizeExp": ["组织经历描述"],
              "projects": ["项目描述"],
              "selfEvaluation": "自我评价"
            }
            
            注意：
            1. 如果某个字段在简历中没有明确提到，请保持对应的JSON值为空数组[]、空列表[]或空字符串""
            2. 日期格式必须严格按照要求，不能使用"至今"、"当前"等词语
            3. 对于不完整的日期（如只有年份），请尽可能推断并补全为完整格式
            4. 手机号格式为11位数字
            5. 邮箱格式需验证
            6. 不确定的句子都可以写到自我评价里面
            
            以下是需要解析的简历文本：
            
            """
                + resumeText
                + """
            
            请只返回JSON对象，不要包含任何解释性文字。
            """;
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