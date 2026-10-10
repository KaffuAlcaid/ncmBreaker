package com.ncmbreaker.netease.music;

public enum AudioQuality {
    VIVID("vivid", "臻音全景声", "vi"),
    SKY("sky", "沉浸环绕声", "sk"),
    MASTER("jymaster", "超清母带", "jm"),
    EFFECT("jyeffect", "高清臻音", "je"),
    HIRES("hires", "高解析度无损", "hr"),
    LOSSLESS("lossless", "无损", "sq"),
    EXHIGH("exhigh", "极高", "h"),
    STANDARD("standard", "标准", "l");

    private final String level;
    private final String label;
    private final String resourceKey;

    AudioQuality(String level, String label, String resourceKey) {
        this.level = level;
        this.label = label;
        this.resourceKey = resourceKey;
    }

    public String level() { return level; }
    public String resourceKey() { return resourceKey; }
    @Override public String toString() { return label; }

    public static AudioQuality fromLevel(String level) {
        for (var value : values()) {
            if (value.level.equals(level)) return value;
        }
        return null;
    }
}
