package group.userservice;

import group.userservice.domain.po.User_;
import group.userservice.mapper.UserMapper;


import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.security.KeyPair;
import java.util.List;

@SpringBootTest
class UserServiceApplicationTests {
    @Autowired
    private UserMapper userMapper;

    @Autowired
    private KeyPair keyPair;
    @Test
    void testMybatis() {
        List<User_> users = userMapper.selectList(null);
        users.forEach(System.out::println);
//        User_ user = userMapper.selectById(1);
//        System.out.println(user);

    }

    @Test
    void test2(){
        User_ user = new User_();
        user = userMapper.queryById(1);
        System.out.println(user);
    }

    @Test
    void testInsert() {
        User_ user = new User_();
//        user.set(5L);
//        user.setName("Lucy");
//        user.setPassword("123");

        userMapper.insert(user);
    }

    @Test
    void keyPairTest(){
        // 检查KeyPair是否成功注入
        if (keyPair == null) {
            System.out.println("❌ KeyPair Bean 不存在");
        } else {
            System.out.println("✅ KeyPair Bean 已创建");
            System.out.println("  算法: " + keyPair.getPublic().getAlgorithm());
        }

    }

}
