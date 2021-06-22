package com;

import java.io.File;
import java.io.IOException;

/**
 * @Description:
 * @author: LinQin
 * @date: 2019/06/14
 */
public class Test {
    public static void main(String[] args) throws IOException {
        // File file = new File("F:\\hexo\\vuepress\\docs\\.vuepress\\picBak\\1549090961408.png");
        // byte[] bytes = Files.readAllBytes(file.toPath());
        // System.out.println("data:image/png;base64," + Base64.getEncoder().encodeToString(bytes));

        // Files.copy(Paths.get("F:\\hexo\\vuepress\\docs\\README.md"), Paths.get("F:\\hexo\\vuepress\\docs\\README1.md"), StandardCopyOption.REPLACE_EXISTING);

        File fileName = new File("F:\\hexo\\vuepress\\docs\\assets\\mul_thread.gif");

        System.out
                .println(getRelativePath("F:/hexo/vuepress/docs/Java学习/IDEA学习", "F:/hexo/vuepress/docs/assets/mul_thread.gif"));

    }
    /**
     * 获得targetPath相对于sourcePath的相对路径
     * @param sourcePath	: 原文件路径
     * @param targetPath	: 目标文件路径
     * @return
     */
    private static String getRelativePath(String sourcePath, String targetPath) {
        StringBuffer pathSB = new StringBuffer();

        if (targetPath.indexOf(sourcePath) == 0){
            pathSB.append(targetPath.replace(sourcePath, ""));
        }else {
            String[] sourcePathArray = sourcePath.split("/");
            String[] targetPathArray = targetPath.split("/");
            // if (targetPathArray.length >= sourcePathArray.length){
                for (int i = 0; i < targetPathArray.length; i++){
                    if (sourcePathArray.length > i && targetPathArray[i].equals(sourcePathArray[i])){
                        continue;
                    }else {
                        for (int j = i; j < sourcePathArray.length; j++){
                            pathSB.append("../");
                        }
                        for (;i < targetPathArray.length; i++){
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
