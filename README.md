# NCM Breaker

NCM Breaker 支持网易云音乐下载、歌单管理和本地 NCM 文件转换

**[下载 Windows 版](https://github.com/KaffuAlcaid/ncmBreaker/releases/download/v0.4.0/NcmBreaker-0.4.0-windows-x86_64.zip)** · [下载 Linux 版](https://github.com/KaffuAlcaid/ncmBreaker/releases/download/v0.4.0/NcmBreaker-0.4.0-linux-x86_64.tar.gz) · [通用 JAR](https://github.com/KaffuAlcaid/ncmBreaker/releases/download/v0.4.0/NcmBreaker-0.4.0.jar) · [独立命令行 JAR](https://github.com/KaffuAlcaid/ncmBreaker/releases/download/v0.4.0/NcmBreaker-0.4.0-cli.jar) · [全部版本](https://github.com/KaffuAlcaid/ncmBreaker/releases)

## 主要功能

- 支持网易云扫码登录，获取当前账号的我喜欢的音乐/创建的歌单/收藏的歌单，并选择导出为 XLSX
- 支持搜索歌曲、歌单和专辑，或通过 ID/链接获取内容，选择音质下载单曲或批量下载，为 MP3、FLAC 嵌入歌曲信息和专辑封面
- 支持批量将 NCM 文件转换为 MP3 或 FLAC

## v0.4.0 更新

- 新增歌曲、歌单和专辑搜索，支持搜索建议、历史记录与分页浏览
- 下载和本地转换支持联网补全歌曲资料、保存或嵌入歌词、导出独立封面、生成 M3U8 歌单，以及目录整理和自定义命名模板
- 下载任务支持队列暂停、失败重试与重启后恢复；下载前可对比已有文件，选择补下缺失歌曲或下载其他音质
- 下载前显示所选音质的预计音频大小，批量与增量选择同步更新统计，并标明大小未知的歌曲
- 修复网易云音乐 3.x NCM 容器的封面帧解析，正确定位含封面填充区文件的音频数据

## 开始使用

Windows 解压后运行 `NcmBreaker.exe`，Linux 运行 `NcmBreaker/bin/NcmBreaker`。发行包自带 Java

扫码登录后，在“音乐下载”中搜索歌曲、歌单或专辑，也可切换到“链接或 ID”获取内容。选歌后选择音质与输出选项，歌单通过“导入歌单”和“导出歌单”读写 XLSX

本地 NCM 在“本地转换”中添加或拖入文件，选择输出目录后点击“开始转换”，确认处理选项后执行。联网补全资料和获取歌词需要先登录

在“下载任务”中查看进度、暂停队列或重试失败任务；重新打开程序并登录原账号后，可通过“继续上次任务”恢复未完成的下载

登录状态保存在程序同目录的 `session/session.json`，偏好设置保存在 `config/settings.json`，下载记录保存在 `config/tasks/`。更新时保留 `session` 和 `config` 文件夹

<details>
<summary>使用通用 JAR 与命令行</summary>

安装 Java 17 或更高版本，在 JAR 所在目录打开终端

启动图形界面：

```text
java -jar NcmBreaker-0.4.0.jar
```

检查 NCM 文件信息：

```text
java -jar NcmBreaker-0.4.0.jar --inspect "音乐目录"
```

将 NCM 文件或文件夹转换到指定目录：

```text
java -jar NcmBreaker-0.4.0.jar -o "输出目录" "音乐目录"
```

仅使用命令行时，可下载体积更小的 `NcmBreaker-0.4.0-cli.jar`，使用相同的 `--inspect`、`-o` 参数

</details>

## 文档与反馈

- [NCM 加密流程](https://github.com/KaffuAlcaid/ncmBreaker/blob/master/docs/ncm-classic-encryption.md)：容器结构、密钥、元数据和音频的组织方式
- [NCM 解密流程](https://github.com/KaffuAlcaid/ncmBreaker/blob/master/docs/ncm-classic-decryption.md)：检测、解密与音频恢复过程
- [反馈问题](https://github.com/KaffuAlcaid/ncmBreaker/issues/new)：请附系统、程序版本、操作步骤和提示信息

## 许可与致谢

采用 [MIT License](https://github.com/KaffuAlcaid/ncmBreaker/blob/master/LICENSE)，由 [KaffuAlcaid](https://github.com/KaffuAlcaid) 开发维护

NCM Breaker 是独立开发的开源项目，与网易云音乐及其关联方无隶属或合作关系。请在适用法律法规和服务协议允许的范围内，处理合法取得且有权转换的文件

感谢 [charlotte-xiao/NCM2MP3](https://github.com/charlotte-xiao/NCM2MP3) 对 NCM 格式和算法的分析，以及 [LwhJesse/Netease-Playlist-Exporter](https://github.com/LwhJesse/Netease-Playlist-Exporter) 提供的歌单获取与数据导出思路
