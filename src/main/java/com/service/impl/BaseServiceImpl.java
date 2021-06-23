package com.service.impl;

import com.constant.GiteeConfig;
import com.constant.RuleConfig;
import com.dao.InfoMapper;
import com.entity.Info;
import com.entity.gitee.UploadRequest;
import com.entity.gitee.UploadResponse;
import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.service.API;
import com.service.BaseService;
import com.util.DownloadUploadPic;
import com.util.FileUtil;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import javax.annotation.PostConstruct;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * @Description:
 * @author: LinQin
 * @date: 2019/05/31
 */
@Service
@Slf4j
public class BaseServiceImpl implements BaseService {
    // <img src="F:\hexo\vuepress\docs\.vuepress\picBak\1554094154356.png" alt="项目结构" style="zoom:67%;" />
    // \s\<img.*?src\=(\'|\")(.*?)(\'|\")[^>]*>

    /**
     * 匹配： ![下载地址](https://i.loli.net/2019/07/30/5d400294d103e20393.jpg)
     * 同时也匹配： ![mul_thread.gif](../../assets/mul_thread.gif)
     */
    final static String regex1 = "\\s*!\\[.*\\]\\(.*?\\)$";

    private final static Pattern PATTERN1;
    /**
     * 匹配括号内的字符串或则src后面的路径
     */
    private final static Pattern ALL = Pattern.compile("(?<=src=\\\").*?(?=\\\")|(?<=\\().*?(?=\\))");

    static {
        PATTERN1 = Pattern.compile(regex1, Pattern.MULTILINE);
    }

    @Autowired
    private InfoMapper infoMapper;

    @Autowired
    private RuleConfig ruleConfig;

    @Autowired
    private GiteeConfig giteeConfig;

    @Value("#{'${markdown.exclude-list}'.split(',')}")
    private List<String> excludedList;

    @Value("${markdown.local-path}")
    private String localPath;

    @Getter
    private Map<String, List<Pattern>> map = new HashMap<>();


    @Autowired
    private API api;

    @PostConstruct
    public void init() {
        List<String> upload = ruleConfig.getUpload();
        List<Pattern> uploadPatterns = upload.stream().map(item -> Pattern.compile(item, Pattern.MULTILINE))
                                             .collect(Collectors.toList());
        map.put("upload", uploadPatterns);
    }

    /**
     * 备份md文件的图片
     *
     * @param markDownFilePath 笔记目录
     * @param bakPath          备份图片保存的路径
     */
    @Override
    public void bak(String markDownFilePath, String bakPath) throws IOException {
        File file = new File(markDownFilePath);
        List<File> markDownFileLists = FileUtil.getMarkDownFile(file);
        markDownFileLists.forEach(System.out::println);

        for (File file1 : markDownFileLists) {
            List<String> content = FileUtil.readFileContent(file1);
            List<String> matchContent = FileUtil.matchContent(content, PATTERN1);
            matchContent.stream().map(item -> {
                int start = item.indexOf("(");
                item = item.substring(start + 1, item.length() - 1);
                return item;
            }).forEach(downUploadUrl -> {
                // 下载图片
                try {
                    Info info = infoMapper.selectByLocalOrPicUrl(downUploadUrl);
                    if (info != null) {
                        String localUrl = bakPath + File.separator + info.getPicName();
                        if (downUploadUrl.contains("https")) {
                            log.info("下载链接: {}", downUploadUrl);
                            DownloadUploadPic.download(downUploadUrl, localUrl);
                        } else {
                            File b = new File(file1.getParent(), downUploadUrl);
                            String absolute = b.getCanonicalPath();

                            Files.copy(Paths.get(absolute), Paths.get(localUrl), StandardCopyOption.REPLACE_EXISTING);
                        }
                    } else {
                        log.info("file: {}", file1.getAbsolutePath());
                    }

                } catch (Exception e) {
                    log.error("下载失败: {}", downUploadUrl);
                    e.printStackTrace();
                }

            });
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void upload(String markDownFilePath) throws IOException {
        File file = new File(markDownFilePath);
        List<File> markDownFileList = FileUtil.getMarkDownFile(file);

        List<Pattern> upload = map.get("upload");
        List<String> uploadResults = new ArrayList<>();
        for (File fileName : markDownFileList) {
            List<String> content = FileUtil.readFileContent(fileName);

            List<String> obsoleteUrls = new LinkedList<>();
            List<String> contentList = new LinkedList<>();


            for (Pattern pattern : upload) {
                // 获取匹配字符串：![下载地址](assets/1550052930676.png)
                List<String> mdStr = FileUtil.matchContent(content, pattern);
                contentList.addAll(mdStr);

                // 获取绝对路径
                List<String> collect = mdStr.stream().map(item -> {
                    Matcher matcher = ALL.matcher(item);
                    if (matcher.find()) {
                        String url = matcher.group(0);
                        // 判断是相对路径还是绝对路径
                        if (FileUtil.isAbsolutelyPath(url)) {
                            return url;
                        }
                    }
                    // 拼接路径
                    int i = item.indexOf("(");
                    String path = item.substring(i + 1, item.length() - 1);
                    path = fileName.getParent() + File.separator + path;
                    return path;
                }).collect(Collectors.toList());
                obsoleteUrls.addAll(collect);

            }

            // 上传
            List<String> list = Lists.newArrayList();
            for (String s : obsoleteUrls) {
                if (!ruleConfig.isHttpUpload()) {
                    if (s.contains("http")) {
                        continue;
                    }
                }
                try {
                    // 获取文件名, 取最大的
                    String picName = getFileName(s);
                    // 复制文件到 assert 文件夹下面
                    String copyPath = copyToLocal(s, picName);
                    // 替换原来的图片路径
                    byte[] bytes = Files.readAllBytes(new File(s).toPath());
                    UploadRequest uploadRequest = new UploadRequest(giteeConfig.getAccessToken(),
                            Base64.getEncoder().encodeToString(bytes), picName, "auto commit", fileName
                            .getAbsolutePath());
                    UploadResponse response = api.upload(uploadRequest);
                    // UploadResponse response = null;
                    if (response == null) {
                        log.error("文件:{}, 路径:{}", fileName.getName(), s);
                        continue;
                    }

                    String download_url = response.getContent().getDownload_url();
                    String md = "![%s](%s)";
                    // 保存入库
                    Info info = new Info();
                    info.setPicName(picName);
                    info.setPicUrl(download_url);
                    // 使用相对路径
                    String relativePath = getRelativePath(fileName.getParent().replace("\\", "/"), copyPath);
                    if (relativePath.lastIndexOf("/") != -1) {
                        relativePath = relativePath.substring(0, relativePath.length() - 1);
                    }
                    info.setPicLocalPath(relativePath);

                    if (picIsValid(download_url, excludedList)) {
                        info.setSha(response.getContent().getSha());
                        String format = String.format(md, picName, download_url);
                        list.add(format);
                    } else {
                        log.debug("图片无效: path: {}, url: {}", s, download_url);
                        String format = String.format(md, picName, relativePath);
                        list.add(format);
                    }
                    infoMapper.insertOrUpdate(info);
                } catch (Exception e) {
                    e.printStackTrace();
                    list.add(null);
                }
            }
            uploadResults.addAll(list);

            // 更新替换文本
            if (!CollectionUtils.isEmpty(list)) {
                if (contentList.size() != list.size()) {
                    log.error("错误文件：{}, 请检查！", fileName.getName());
                    continue;
                }
                List<String> newContent = replacePicPath(content, contentList, list);

                String join = Joiner.on(System.lineSeparator()).join(newContent);
                ByteArrayInputStream bis = new ByteArrayInputStream(join.getBytes());
                Files.copy(bis, fileName.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        log.info("上传结果");
        uploadResults.forEach(System.out::println);
    }

    private String copyToLocal(String s, String picName) throws IOException {
        String copyPath = localPath + "/" + picName;
        log.debug("copyPath:{}", copyPath);
        Files.copy(Paths.get(s), Paths.get(copyPath), StandardCopyOption.REPLACE_EXISTING);
        return copyPath;
    }

    private String getFileName(String s) {
        int i = s.lastIndexOf("/");
        int j = s.lastIndexOf("\\");
        if (i < j) i = j;
        return s.substring(i + 1);
    }

    @Override
    public void check(String path) throws IOException {
        File file = new File(path);
        List<File> markDownFileList = FileUtil.getMarkDownFile(file);
        markDownFileList.forEach(System.out::println);

        for (File item : markDownFileList) {
            List<String> content = FileUtil.readFileContent(item);
            // 匹配： ![下载地址](https://i.lsd83c93567.jpg)
            List<String> urlList = FileUtil.matchContent(content, PATTERN1);
            Map<String, String> checkMap = Maps.newHashMap();

            urlList.forEach(url -> {
                String picHttpUrl = url.substring(url.indexOf("(") + 1, url.length() - 1);
                // boolean valid = DownloadUploadPic.isValid(picHttpUrl);
                boolean picIsValid = DownloadUploadPic.picIsValid(picHttpUrl);
                // log.info("地址：{} {}", picHttpUrl, valid);
                if (!picIsValid) {
                    log.info("失效文件：{}, url: {}", item, url);

                }
            });

            //  md 替换
            // todo 上传处理
            if (ruleConfig.isCheckReplace()) {
                if (!CollectionUtils.isEmpty(checkMap)) {
                    List<String> collect = content.stream().map(c -> {
                        if (checkMap.containsKey(c)) {
                            c = checkMap.get(c);
                            log.info("替换链接失效图片：{}", checkMap.get(c));
                        }
                        return c;
                    }).collect(Collectors.toList());

                    replaceMd(item, collect);
                }
            }
        }

    }

    @Override
    public void local(String path) throws IOException {
        File file = new File(path);
        List<File> markDownFileList = FileUtil.getMarkDownFile(file);
        markDownFileList.forEach(System.out::println);

        for (File fileName : markDownFileList) {
            List<String> content = FileUtil.readFileContent(fileName);
            // 获取匹配字符串：![下载地址](https://i.lsd83c93567.jpg
            List<String> urlList = FileUtil.matchContent(content, PATTERN1);
            if (CollectionUtils.isEmpty(urlList)) continue;

            Map<String, String> replaceMap = urlList.stream().collect(Collectors.toMap(String::toString, item -> {
                String picHttpUrl = item.substring(item.indexOf("(") + 1, item.length() - 1);
                Info info = infoMapper.selectByPicUrl(picHttpUrl);
                String replace = item.replace(picHttpUrl, info.getPicLocalPath());
                return replace;
            }));

            List<String> collect = getReplaceContent(content, replaceMap);

            // 替换为 md
            replaceMd(fileName, collect);
        }
    }

    private List<String> getReplaceContent(List<String> content, Map<String, String> replaceMap) {
        return content.stream().map(item -> {
            if (replaceMap.containsKey(item)) {
                return replaceMap.get(item);
            } else {
                return item;
            }
        }).collect(Collectors.toList());
    }

    @Override
    public void httpUrl(String path) throws IOException {
        File file = new File(path);
        List<File> markDownFileList = FileUtil.getMarkDownFile(file);
        markDownFileList.forEach(System.out::println);

        for (File fileName : markDownFileList) {
            List<String> content = FileUtil.readFileContent(fileName);
            // 获取匹配字符串：![下载地址](F:\GitBook\Markdown入门到放弃\bak\1550052930676.png)
            List<String> urlModel = FileUtil.matchContent(content, Pattern.compile(""));

            if (CollectionUtils.isEmpty(urlModel)) continue;
            Map<String, String> replaceMap = urlModel.stream().collect(Collectors.toMap(String::toString, item -> {
                String picLocalPath = item.substring(item.indexOf("(") + 1, item.length() - 1);
                Info info = infoMapper.selectByPicLocal(picLocalPath);
                String replace = item.replace(picLocalPath, info.getPicUrl());
                return replace;
            }));

            List<String> collect = getReplaceContent(content, replaceMap);

            // 替换为 md
            replaceMd(fileName, collect);
        }
    }


    private void replaceMd(File fileName, List<String> collect) throws IOException {
        String newCcontent = Joiner.on(System.lineSeparator()).join(collect);
        ByteArrayInputStream bis = new ByteArrayInputStream(newCcontent.getBytes(
                StandardCharsets.UTF_8));
        Files.copy(bis, fileName.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * @param filePathList assert/xxx.png
     * @param picPath      图床地址
     */
    private List<String> replacePicPath(List<String> content, List<String> filePathList,
            List<String> picPath) {
        List<String> list = Lists.newArrayList();
        for (String contentUrl : content) {
            for (int j = 0; j < filePathList.size(); j++) {
                String oldUrl = filePathList.get(j);
                if (contentUrl.contains(oldUrl) && picPath.get(j) != null) {
                    contentUrl = contentUrl.replace(oldUrl, picPath.get(j));
                }

            }
            list.add(contentUrl);
        }
        return list;
    }

    public boolean picIsValid(String localStr, List<String> excludedList) {
        try {
            InputStream inputStream = null;
            if (!localStr.contains("http")) {
                File f = new File(localStr);
                inputStream = new FileInputStream(f);
            } else {
                inputStream = new URL(localStr).openStream();
            }
            try {
                BufferedImage sourceImg = ImageIO.read(inputStream);//判断图片是否损坏
                int picWidth = sourceImg.getWidth(); //确保图片是正确的（正确的图片可以取得宽度）
                return true;
            } catch (Exception e) {
                //关闭IO流才能操作图片
                inputStream.close();
                // e.printStackTrace();
                int i = localStr.lastIndexOf("/");
                int j = localStr.lastIndexOf("\\");
                if (i < j) i = j;
                if (excludedList.contains(localStr.substring(i + 1))) {
                    log.info("配置无法识别图片: {}", localStr);
                    return true;
                }
                return false;
            } finally {
                //最后一定要关闭IO流
                inputStream.close();
            }
        } catch (Exception e) {
            // e.printStackTrace();
            return false;
        }
    }

    /**
     * 获得targetPath相对于sourcePath的相对路径
     *
     * @param sourcePath : 原文件路径
     * @param targetPath : 目标文件路径
     * @return
     */
    private String getRelativePath(String sourcePath, String targetPath) {
        StringBuffer pathSB = new StringBuffer();

        if (targetPath.indexOf(sourcePath) == 0) {
            pathSB.append(targetPath.replace(sourcePath, ""));
        } else {
            String[] sourcePathArray = sourcePath.split("/");
            String[] targetPathArray = targetPath.split("/");
            // if (targetPathArray.length >= sourcePathArray.length){
            for (int i = 0; i < targetPathArray.length; i++) {
                if (sourcePathArray.length > i && targetPathArray[i].equals(sourcePathArray[i])) {
                    continue;
                } else {
                    for (int j = i; j < sourcePathArray.length; j++) {
                        pathSB.append("../");
                    }
                    for (; i < targetPathArray.length; i++) {
                        pathSB.append(targetPathArray[i] + "/");
                    }
                    break;
                }
            }
            // }else {
            //     for (int i = 0; i < sourcePathArray.length; i++){
            //         if (targetPathArray.length > i && targetPathArray[i].equals(sourcePathArray[i])){
            //             continue;
            //         }else {
            //             for (int j = i; j < sourcePathArray.length; j++){
            //                 pathSB.append("../");
            //             }
            //             break;
            //         }
            //     }
            // }

        }
        return pathSB.toString();
    }

}
