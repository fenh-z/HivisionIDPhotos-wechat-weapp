package org.zjzWx.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("custom")
public class Custom {

    @TableId(type = IdType.AUTO)
    private Integer id;
    /**
     * 用户id
     */
    private Integer userId;
    /**
     * 名称
     */
    private String name;
    /**
     * 像素-宽
     */
    private Integer widthPx;
    /**
     * 像素-高
     */
    private Integer heightPx;
    /**
     * 尺寸-宽
     */
    private Integer widthMm;
    /**
     * 尺寸-高
     */
    private Integer heightMm;
    /**
     * 分辨率
     */
    private Integer dpi;

    @TableField(exist = false)
    private String size;

    /**
     * 图标，1-6
     */
    private Integer icon;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date createTime;


}
