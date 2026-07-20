package group.careerservice.TestConstant;/* I love coding */

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

public class AI_recommendation {
    public static String recommendation = "{\n" +
            "    \"bestMatch\": {\n" +
            "        \"jobId\": \"job_fe_junior\",\n" +
            "        \"jobName\": \"前端开发工程师\",\n" +
            "        \"companyName\": \"明杉科技\",\n" +
            "        \"city\": \"西安\",\n" +
            "        \"educationRequirement\": \"本科\",\n" +
            "        \"salaryNegotiable\": false,\n" +
            "        \"salaryNormalized\": \"10-16k/月·13薪\",\n" +
            "        \"updatedAtRaw\": \"7月23日\",\n" +
            "        \"level\": \"junior\",\n" +
            "        \"overallScore\": 84,\n" +
            "        \"matchTags\": [\n" +
            "            \"高匹配\",\n" +
            "            \"优势维度：职业技能\"\n" +
            "        ],\n" +
            "        \"dimensionScores\": {\n" +
            "            \"basicRequirement\": 86,\n" +
            "            \"professionalSkill\": 82,\n" +
            "            \"professionalLiteracy\": 80,\n" +
            "            \"developmentPotential\": 83\n" +
            "        }\n" +
            "    },\n" +
            "    \"otherRecommendations\": [\n" +
            "        {\n" +
            "            \"jobId\": \"job_fullstack_mid\",\n" +
            "            \"jobName\": \"全栈开发工程师\",\n" +
            "            \"companyName\": \"云桥科技\",\n" +
            "            \"city\": \"西安\",\n" +
            "            \"educationRequirement\": \"本科\",\n" +
            "            \"salaryNegotiable\": false,\n" +
            "            \"salaryNormalized\": \"16-26k/月\",\n" +
            "            \"updatedAtRaw\": \"7月21日\",\n" +
            "            \"level\": \"middle\",\n" +
            "            \"overallScore\": 79,\n" +
            "            \"matchTags\": [\n" +
            "                \"潜力匹配\"\n" +
            "            ]\n" +
            "        }\n" +
            "    ]\n" +
            "  }";
    public static String analysis = "{\n" +
            "     \"job\": {\n" +
            "          \"jobId\": \"job_fe_junior\",\n" +
            "          \"jobName\": \"前端开发工程师\",\n" +
            "          \"companyName\": \"明杉科技\",\n" +
            "          \"city\": \"西安\",\n" +
            "          \"industryTags\": [\n" +
            "            \"互联网\",\n" +
            "            \"数字化服务\"\n" +
            "          ],\n" +
            "          \"educationRequirement\": \"本科\",\n" +
            "          \"salaryNegotiable\": false,\n" +
            "          \"salaryNormalized\": \"10-16k/月·13薪\",\n" +
            "          \"updatedAtRaw\": \"7月23日\",\n" +
            "          \"level\": \"junior\"\n" +
            "    },\n" +
            "    \"analysis\": {\n" +
            "      \"overallScore\": 84,\n" +
            "      \"dimensionScores\": {\n" +
            "        \"basicRequirement\": 86,\n" +
            "        \"professionalSkill\": 82,\n" +
            "        \"professionalLiteracy\": 80,\n" +
            "        \"developmentPotential\": 83\n" +
            "      },\n" +
            "      \"jobExpectedScores\": {\n" +
            "        \"basicRequirement\": 78,\n" +
            "        \"professionalSkill\": 75,\n" +
            "        \"professionalLiteracy\": 68,\n" +
            "        \"developmentPotential\": 61\n" +
            "      },\n" +
            "      \"studentAbilityScores\": {\n" +
            "        \"professionalSkill\": 78,\n" +
            "        \"certificate\": 62,\n" +
            "        \"innovation\": 70,\n" +
            "        \"internalMotivation\": 74,\n" +
            "        \"learning\": 88,\n" +
            "        \"stressTolerance\": 72,\n" +
            "        \"communication\": 80,\n" +
            "        \"internship\": 74,\n" +
            "        \"language\": 67,\n" +
            "        \"leadership\": 61,\n" +
            "        \"adaptability\": 76,\n" +
            "        \"execution\": 73\n" +
            "      },\n" +
            "      \"jobAbilityScores\": {\n" +
            "        \"professionalSkill\": 84,\n" +
            "        \"certificate\": 72,\n" +
            "        \"innovation\": 75,\n" +
            "        \"internalMotivation\": 70,\n" +
            "        \"learning\": 82,\n" +
            "        \"stressTolerance\": 68,\n" +
            "        \"communication\": 78,\n" +
            "        \"internship\": 80,\n" +
            "        \"language\": 70,\n" +
            "        \"leadership\": 65,\n" +
            "        \"adaptability\": 74,\n" +
            "        \"execution\": 79\n" +
            "      },\n" +
            "      \"dimensionAnalysis\": {\n" +
            "        \"basicRequirement\": {\n" +
            "          \"label\": \"基础要求\",\n" +
            "          \"score\": 86,\n" +
            "          \"expectedScore\": 78,\n" +
            "          \"reason\": \"在基础素养维度，整体具备基础匹配度，但仍需针对关键能力进行强化以提升稳定通过率。\",\n" +
            "          \"confidence\": \"high\"\n" +
            "        },\n" +
            "        \"professionalSkill\": {\n" +
            "          \"label\": \"职业技能\",\n" +
            "          \"score\": 82,\n" +
            "          \"expectedScore\": 77,\n" +
            "          \"reason\": \"在职业技能维度，整体具备基础匹配度，但仍需针对关键能力进行强化以提升稳定通过率。\",\n" +
            "          \"confidence\": \"medium\"\n" +
            "        },\n" +
            "        \"professionalLiteracy\": {\n" +
            "          \"label\": \"职业素养\",\n" +
            "          \"score\": 80,\n" +
            "          \"expectedScore\": 73,\n" +
            "          \"reason\": \"在职业素养维度，候选人已覆盖当前岗位的关键要求，建议通过项目深度进一步拉开优势。\",\n" +
            "          \"confidence\": \"medium\"\n" +
            "        },\n" +
            "        \"developmentPotential\": {\n" +
            "          \"label\": \"发展潜力\",\n" +
            "          \"score\": 83,\n" +
            "          \"expectedScore\": 69,\n" +
            "          \"reason\": \"在发展潜力维度，候选人已覆盖当前岗位的关键要求，建议通过项目深度进一步拉开优势。\",\n" +
            "          \"confidence\": \"medium\"\n" +
            "        }\n" +
            "      },\n" +
            "      \"matchTags\": [\n" +
            "        \"高匹配\",\n" +
            "        \"优势维度：职业技能\"\n" +
            "      ],\n" +
            "      \"improvementSuggestions\": [\n" +
            "        {\n" +
            "          \"dimension\": \"certificate\",\n" +
            "          \"priority\": \"high\",\n" +
            "          \"advice\": \"补充与岗位相关的职业证书，形成能力背书\"\n" +
            "        }\n" +
            "      ]\n" +
            "    }\n" +
            "}";
}
