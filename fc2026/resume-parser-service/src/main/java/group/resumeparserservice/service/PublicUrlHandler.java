package group.resumeparserservice.service;/* I love coding */

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.HttpStatus;
import org.apache.http.util.EntityUtils;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

//public class PublicUrlHandler {
//
//    private static final String API_URL = "https://dashscope.aliyuncs.com/api/v1/uploads";
//
//    public static JSONObject getUploadPolicy(String apiKey, String modelName) throws IOException {
//        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
//            HttpGet httpGet = new HttpGet(API_URL);
//            httpGet.addHeader("Authorization", "Bearer " + apiKey);
//            httpGet.addHeader("Content-Type", "application/json");
//
//            String query = String.format("action=getPolicy&model=%s", modelName);
//            httpGet.setURI(httpGet.getURI().resolve(httpGet.getURI() + "?" + query));
//
//            try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
//                if (response.getStatusLine().getStatusCode() != 200) {
//                    throw new IOException("Failed to get upload policy: " +
//                            EntityUtils.toString(response.getEntity()));
//                }
//                String responseBody = EntityUtils.toString(response.getEntity());
//                return new JSONObject(responseBody).getJSONObject("data");
//            }
//        }
//    }
//
//    public static String uploadFileToOSS(JSONObject policyData, String filePath) throws IOException {
//        Path path = Paths.get(filePath);
//        String fileName = path.getFileName().toString();
//        String key = policyData.getString("upload_dir") + "/" + fileName;
//
//        HttpPost httpPost = new HttpPost(policyData.getString("upload_host"));
//        MultipartEntityBuilder builder = MultipartEntityBuilder.create();
//
//        builder.addTextBody("OSSAccessKeyId", policyData.getString("oss_access_key_id"));
//        builder.addTextBody("Signature", policyData.getString("signature"));
//        builder.addTextBody("policy", policyData.getString("policy"));
//        builder.addTextBody("x-oss-object-acl", policyData.getString("x_oss_object_acl"));
//        builder.addTextBody("x-oss-forbid-overwrite", policyData.getString("x_oss_forbid_overwrite"));
//        builder.addTextBody("key", key);
//        builder.addTextBody("success_action_status", "200");
//        byte[] fileContent = Files.readAllBytes(path);
//        builder.addBinaryBody("file", fileContent, ContentType.DEFAULT_BINARY, fileName);
//
//        httpPost.setEntity(builder.build());
//
//        try (CloseableHttpClient httpClient = HttpClients.createDefault();
//             CloseableHttpResponse response = httpClient.execute(httpPost)) {
//            if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
//                throw new IOException("Failed to upload file: " +
//                        EntityUtils.toString(response.getEntity()));
//            }
//            return "oss://" + key;
//        }
//    }
//
//    public static String uploadFileAndGetUrl(String apiKey, String modelName, String filePath) throws IOException {
//        JSONObject policyData = getUploadPolicy(apiKey, modelName);
//        return uploadFileToOSS(policyData, filePath);
//    }
//
//    public static void main(String[] args) {
//        // 获取环境变量中的API密钥
//        String apiKey = "sk-2c1e9d35337d4deb8c7ff28b4f6c4193";
//        if (apiKey == null || apiKey.isEmpty()) {
//            System.err.println("请设置DASHSCOPE_API_KEY环境变量");
//            System.exit(1);
//        }
//
//        String currentDir = System.getProperty("user.dir");
//        System.out.println("当前工作目录: " + currentDir);
//
//        // 模型名称
//        String modelName = "qwen-vl-plus";
//        //替换为实际文件路径
//        String filePath = "D:\\desktop\\university\\a_fuchuang_2026\\fc2026\\resume.png";
//
//        try {
//            // 检查文件是否存在
//            File file = new File(filePath);
//            if (!file.exists()) {
//                System.err.println("文件不存在: " + filePath);
//                System.exit(1);
//            }
//
//            String publicUrl = uploadFileAndGetUrl(apiKey, modelName, filePath);
//            LocalDateTime expireTime = LocalDateTime.now().plusHours(48);
//            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
//
//            System.out.println("文件上传成功，有效期为48小时，过期时间: " + expireTime.format(formatter));
//            System.out.println("临时URL: " + publicUrl);
//            System.out.println("注意：使用oss://形式的临时URL时，必须在HTTP请求头（Header）中显式添加参数：X-DashScope-OssResourceResolve: enable，具体请参考：https://help.aliyun.com/zh/model-studio/get-temporary-file-url#http-call");
//        } catch (IOException e) {
//            System.err.println("Error: " + e.getMessage());
//        }
//    }
//}
