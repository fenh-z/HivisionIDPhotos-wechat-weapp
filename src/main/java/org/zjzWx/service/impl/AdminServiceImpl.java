package org.zjzWx.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.zjzWx.dao.AdminDao;
import org.zjzWx.entity.*;
import org.zjzWx.model.dto.ExploreIndexAdminDto;
import org.zjzWx.model.vo.AdminIndexVo;
import org.zjzWx.model.vo.AdminLoginVo;
import org.zjzWx.model.vo.ChartDataVo;
import org.zjzWx.service.*;
import org.zjzWx.util.PicUtil;
import org.zjzWx.util.R;

import java.time.*;
import java.util.*;

@Service
public class AdminServiceImpl extends ServiceImpl<AdminDao, Admin> implements AdminService {

    @Value("${webset.envVersion}")
    private String envVersion;
    @Value("${webset.directory}")
    private String directory;

    @Autowired
    private WebSetService webSetService;
    @Autowired
    private UserService userService;
    @Autowired
    private UploadService uploadService;
    @Autowired
    private PhotoRecordService photoRecordService;
    @Autowired
    private ItemService itemService;
    @Autowired
    private CustomService customService;
    @Autowired
    private PhotoService photoService;
    @Autowired
    private WebGlowService webGlowService;
    @Autowired
    private AppSetService appSetService;


    @Override
    public AdminLoginVo login() {
        try {
            long code = System.currentTimeMillis();
            WebSet webSet = webSetService.getOne(null);
            Map<String, Object> mp = new HashMap<>();
            mp.put("scene", code);
            mp.put("page", "pages/admin/index");
            mp.put("check_path", false);
            mp.put("env_version", envVersion); //要打开的小程序版本。正式版为 "release"，体验版为 "release"，开发版为 "develop"

            //获取access_token
            String url1 = "https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid=" + webSet.getAppId() + "&secret=" + webSet.getAppSecret();
            RestTemplate restTemplate = new RestTemplate();
            ResponseEntity<String> response = restTemplate.exchange(url1, HttpMethod.GET, null, String.class);
            JSONObject jsonopenid = JSONObject.parseObject(response.getBody());
            String accessToken = jsonopenid.getString("access_token");


            // 获取小程序码
            String url2 = "https://api.weixin.qq.com/wxa/getwxacodeunlimit?access_token=" + accessToken;
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(JSONObject.toJSONString(mp), headers);
            ResponseEntity<byte[]> byteResponse = restTemplate.exchange(url2, HttpMethod.POST, entity, byte[].class);


            //将响应字节数组转换为 MultipartFile 再转 base64
            MultipartFile multipartFile = new MockMultipartFile("file", "qrcode.png", "image/jpg", byteResponse.getBody());
            R data = uploadService.uploadPhoto(multipartFile, "qrcode.png");

            //无论成功失败，清理所有记录，防止打开多个网页标签而导致多次生成的问题
            baseMapper.delete(null);

            if (data.getCode() == 200) {
                Admin admin = new Admin();
                admin.setCode(code);
                baseMapper.insert(admin);

                AdminLoginVo adminLoginVo = new AdminLoginVo();
                adminLoginVo.setPic(data.getData().toString());
                adminLoginVo.setCode(code);
                return adminLoginVo;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }


    @Override
    public String checkLogin(String code) {
        QueryWrapper<Admin> qw = new QueryWrapper<>();
        qw.eq("code", code);
        Admin admin = baseMapper.selectOne(qw);
        if (null != admin && admin.getStatus() == 1) {
            StpUtil.login(1);
            baseMapper.delete(null);
            return StpUtil.getTokenInfo().getTokenValue();
        }
        return null;
    }


    @Override
    public String okLogin(String code1, String code2) {
        try {
            QueryWrapper<Admin> qw = new QueryWrapper<>();
            qw.eq("code", code2);
            Admin admin = baseMapper.selectOne(qw);
            if (null == admin) {
                return "登录请求已失效，请重新刷新二维码";
            }
            if (admin.getStatus() == 1) {
                return "已登录，无需重复登录";
            }

            WebSet webSet = webSetService.getById(1);
            String url = "https://api.weixin.qq.com/sns/jscode2session?appid=" + webSet.getAppId()
                    + "&secret=" + webSet.getAppSecret() + "&js_code=" + code1 + "&grant_type=authorization_code";

            //发起请求
            RestTemplate restTemplate = new RestTemplate();
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            JSONObject jsonopenid = JSONObject.parseObject(response.getBody());
            if (null == jsonopenid) {
                return "与微信通讯失败，请重试";
            }

            String openid = jsonopenid.getString("openid");
            // 高风险的微信用户/数据库配置错误/安全域名没有添加会存在openid没有的情况
            if (null == openid) {
                return jsonopenid.toString();
            }

            QueryWrapper<User> qwuser = new QueryWrapper<>();
            qwuser.eq("openid", openid);
            User user = userService.getOne(qwuser);
            if (null == user) {
                return "您未注册，无法检查您是否为管理员";
            }

            if (1 == user.getId()) {
                admin.setStatus(1);
                baseMapper.updateById(admin);
                return null;
            } else {
                return "您不是管理员，无法登录";
            }
        } catch (Exception e) {
            e.printStackTrace();
            return "代码报错";
        }
    }


    @Override
    public AdminIndexVo adminIndex() {
        // 获取当前日期
        ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
        LocalDate today = now.toLocalDate();
        // 当天的开始时间
        LocalDateTime startOfDay = today.atStartOfDay(); // 默认时区
        // 当天的结束时间
        LocalDateTime endOfDay = LocalDateTime.of(today, LocalTime.MAX); // 使用当天的最后一刻

        AdminIndexVo adminIndexVo = new AdminIndexVo();

        // 统计当天的照片制作次数
        QueryWrapper<PhotoRecord> qw1 = new QueryWrapper<>();
        qw1.ge("create_time", startOfDay)
                .le("create_time", endOfDay)
                .ne("type", 0);
        adminIndexVo.setMakeNum(photoRecordService.count(qw1));

        QueryWrapper<PhotoRecord> qw11 = new QueryWrapper<>();
        qw11.ne("type", 0);
        adminIndexVo.setMakeTotal(photoRecordService.count(qw11));

        // 统计当天的用户数量
        QueryWrapper<User> qw2 = new QueryWrapper<>();
        qw2.ge("create_time", startOfDay)
                .le("create_time", endOfDay);
        adminIndexVo.setUserNum(userService.count(qw2));
        adminIndexVo.setUserTotal(userService.count());

        // 设置总项目数量
        adminIndexVo.setItemTotal(itemService.count() + customService.count());

        // 生成最近7天的日期列表和数据统计
        List<String> timeList = new ArrayList<>();
        List<Integer> dataList = new ArrayList<>();
        LocalDate startDate = today.minusDays(6); // 最近7天的起始日期

        // 生成日期列表
        for (int i = 6; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            timeList.add(date.toString());
        }

        // 查询日期范围内的记录数
        List<Map<String, Object>> counts = photoRecordService.getBaseMapper().selectMaps(
                new QueryWrapper<PhotoRecord>()
                        .select("DATE(create_time) as date", "COUNT(*) as count")
                        .ge("create_time", startDate.atStartOfDay())  // 从最近7天的起始日期
                        .le("create_time", endOfDay)  // 改为当前时间
                        .groupBy("DATE(create_time)")
                        .ne("type", 0)
        );

        // 将查询结果转换为日期-数量的映射
        Map<String, Integer> countMap = new HashMap<>();
        for (Map<String, Object> record : counts) {
            String date = record.get("date").toString();
            Integer count = Integer.valueOf(record.get("count").toString());
            countMap.put(date, count);
        }

        // 组装数据列表
        for (String date : timeList) {
            Integer count = countMap.getOrDefault(date, 0);
            dataList.add(count);
        }

        // 封装数据返回
        ChartDataVo chartData = new ChartDataVo();
        chartData.setTime(timeList);
        chartData.setData(dataList);
        adminIndexVo.setChartDataVo(chartData);


        return adminIndexVo;

    }


    @Override
    public IPage<Item> getItemPage(int pageNum, int pageSize, String name) {
        Page<Item> page = new Page<>(pageNum, pageSize);
        if (null != name && !"".equals(name)) {
            QueryWrapper<Item> qw = new QueryWrapper<>();
            qw.like("name", name);
            return itemService.page(page, qw);
        }
        return itemService.page(page, null);
    }

    @Override
    public IPage<Custom> getCustomPage(int pageNum, int pageSize, int userId) {
        Page<Custom> page = new Page<>(pageNum, pageSize);
        QueryWrapper<Custom> qw = new QueryWrapper<>();
        if (0 != userId) {
            qw.eq("user_id", userId);
        }
        qw.orderByDesc("create_time");
        return customService.page(page, qw);
    }

    @Override
    public IPage<Photo> getPhotoPage(int pageNum, int pageSize, int userId, String name) {
        Page<Photo> page = new Page<>(pageNum, pageSize);
        QueryWrapper<Photo> qw = new QueryWrapper<>();
        if (0 != userId) {
            qw.eq("user_id", userId);
        }
        if (null != name && !"".equals(name)) {
            qw.like("name", name);
        }
        qw.isNotNull("n_img");
        qw.orderByDesc("create_time");
        return photoService.page(page, qw);
    }

    @Override
    public IPage<PhotoRecord> getPhotoRecordPage(int pageNum, int pageSize, int userId) {
        Page<PhotoRecord> page = new Page<>(pageNum, pageSize);
        QueryWrapper<PhotoRecord> qw = new QueryWrapper<>();
        if (0 != userId) {
            qw.eq("user_id", userId);
        }
        qw.orderByDesc("create_time");
        return photoRecordService.page(page, qw);
    }

    @Override
    public IPage<User> getUserPage(int pageNum, int pageSize, int userId, String name) {
        Page<User> page = new Page<>(pageNum, pageSize);
        QueryWrapper<User> qw = new QueryWrapper<>();
        if (0 != userId) {
            qw.eq("id", userId);
        }
        if (null != name && !"".equals(name)) {
            qw.like("nickname", name);
        }
        qw.orderByDesc("create_time");
        return userService.page(page, qw);
    }

    @Override
    public WebSet getWebSet() {
        return webSetService.getById(1);
    }

    @Override
    public void updateWebSet(WebSet webSet) {
        webSet.setId(1);
        webSetService.updateById(webSet);
    }

    @Override
    public WebGlow getWebGlow() {
        return webGlowService.getById(1);
    }

    @Override
    public void updateWebGlow(WebGlow webGlow) {
        webGlow.setId(1);
        webGlowService.updateById(webGlow);
    }

    @Override
    public List<AppSet> getExploreSet() {
        return appSetService.list();
    }

    @Override
    public void updateExploreSet(AppSet appSet) {
        appSetService.updateById(appSet);

    }

    @Override
    public String updateUserStatus(Integer userId, Integer type) {
        //type=1踢掉登录状态，2删除定制记录，3删除保存记录，4删除行为记录，5禁止登录并踢掉登录，6恢复登录
        if (type == 1) {
            StpUtil.kickout(userId);
            return "踢掉成功";
        } else if (type == 2) {
            QueryWrapper<Custom> qw = new QueryWrapper<>();
            qw.eq("user_id", userId);
            customService.remove(qw);
            return "删除成功";
        } else if (type == 3) {
            QueryWrapper<Photo> qw = new QueryWrapper<>();
            qw.eq("user_id", userId);
            List<Photo> list = photoService.list(qw);
            if (null != list && list.size() > 0) {
                for (Photo photo : list) {
                    PicUtil.deleteImage(photo.getNImg(), directory);
                    photoService.removeById(photo);
                }
            }
            return "删除成功";
        } else if (type == 4) {
            QueryWrapper<PhotoRecord> qw = new QueryWrapper<>();
            qw.eq("user_id", userId);
            photoRecordService.remove(qw);
            return "删除成功";
        } else if (type == 5) {
            User user = new User();
            user.setId(userId);
            user.setStatus(2);
            userService.updateById(user);
            StpUtil.kickout(userId);
            return "已禁止并踢掉登录";
        } else if (type == 6) {
            User user = new User();
            user.setId(userId);
            user.setStatus(1);
            userService.updateById(user);
            return "已恢复";
        } else {
            return "非法请求";
        }

    }

    @Override
    public ExploreIndexAdminDto exploreIndexAdmin() {
        ExploreIndexAdminDto exploreIndexAdmin = new ExploreIndexAdminDto();
        List<AppSet> list = appSetService.list();
        for (AppSet appSet : list) {

            if (appSet.getType() == 3) {
                QueryWrapper<PhotoRecord> qw1 = new QueryWrapper<>();
                qw1.in("type", 1, 2, 3, 4);
                exploreIndexAdmin.setZjzCount(photoRecordService.count(qw1));
            }

            if (appSet.getType() == 4) {
                QueryWrapper<PhotoRecord> qw2 = new QueryWrapper<>();
                qw2.eq("type", 7);
                exploreIndexAdmin.setGenerateLayoutCount(photoRecordService.count(qw2));
            }

            if (appSet.getType() == 5) {
                QueryWrapper<PhotoRecord> qw3 = new QueryWrapper<>();
                qw3.eq("type", 5);
                exploreIndexAdmin.setColourizeCount(photoRecordService.count(qw3));
            }

            if (appSet.getType() == 6) {
                QueryWrapper<PhotoRecord> qw4 = new QueryWrapper<>();
                qw4.eq("type", 6);
                exploreIndexAdmin.setMattingCount(photoRecordService.count(qw4));
            }
            if (appSet.getType() == 7) {
                QueryWrapper<PhotoRecord> qw5 = new QueryWrapper<>();
                qw5.eq("type", 9);
                exploreIndexAdmin.setEditImageCount(photoRecordService.count(qw5));
            }

            if (appSet.getType() == 8) {
                QueryWrapper<PhotoRecord> qw6 = new QueryWrapper<>();
                qw6.eq("type", 8);
                exploreIndexAdmin.setCartoonCount(photoRecordService.count(qw6));
            }

        }
        QueryWrapper<PhotoRecord> qw7 = new QueryWrapper<>();
        qw7.eq("type", 10);
        exploreIndexAdmin.setImageuploadCount(photoRecordService.count(qw7));

        QueryWrapper<PhotoRecord> qw8 = new QueryWrapper<>();
        qw8.ne("type", 0);
        exploreIndexAdmin.setImageCount(photoRecordService.count(qw8));


        return exploreIndexAdmin;
    }


}
