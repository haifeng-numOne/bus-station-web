package com.shuzhi.entity;

import com.shuzhi.common.basemapper.BaseEntity;
import com.shuzhi.led.entities.TStatusDto;
import lombok.Data;

import lombok.EqualsAndHashCode;

import javax.persistence.*;
import java.util.Date;
import java.io.Serializable;


/**
 * @author shuzhi
 * @date 2019-07-23 11:31:25
 */

@Table(name = "t_device_station")
@Data
@EqualsAndHashCode(callSuper = true)
public class DeviceStation extends BaseEntity implements Serializable {
    private static final long serialVersionUID = 1L;

    @Id
    private Integer id;

    @Column(name = "stationid")
    private Integer stationid;

    /**
     * 公交站名称
     */
    @Column(name = "station_name")
    private String stationName;

    /**
     * 设备类型编码 1.顶棚照明 2.logo照明 3.站台照明 4.led 5.lcd 6.集中控制器
     */
    @Column(name = "typecode")
    private Integer typecode;

    /**
     * 设备did
     */
    @Column(name = "device_did")
    private String deviceDid;

    /**
     * 设备名称
     */
    @Column(name = "device_name")
    private String deviceName;

    public DeviceStation(String deviceDid) {

        this.deviceDid = deviceDid;

    }

    public DeviceStation() {


    }

}
