package com.shuzhi.websocket.socketvo;

import lombok.Data;
import lombok.ToString;

import java.util.List;

/**
 * @author zgk
 * @description 报文数据
 * @date 2019-07-11 13:32
 */
@Data
public class Msg {

    /**
     * lcd命令类型：1-开启；2-关闭；3-重启；4-调光；5-音量
     */
    private Integer cmdtype;

    /**
     * led命令类型：1-开启；2-关闭；3-重启
     */
    private Integer arg1;

    /**
     * 调光值(lcd不能调光)
     */
    private Integer light;

    /**
     * 音量值
     */
    private Integer volume;

    /**
     * lcd数组 屏id
     */
    private List<String> lcds;

    /**
     * led数组 屏id
     */
    private List<String> leds;

    /**
     * led数组 屏id
     */
    private List<String> lights;

    /**
     * 照明类型：1-灯箱；2-顶棚；3-logo
     */
    private Integer lighttype;

    public void setLighttype(Integer lighttype) {

       switch (lighttype){
           case 1 :
               this.lighttype = 3;
               break;
           case 2 :
               this.lighttype = 1;
               break;
           case 3 :
               this.lighttype = 2;
               break;
       }


    }
}
