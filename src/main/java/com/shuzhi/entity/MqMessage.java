package com.shuzhi.entity;

import com.shuzhi.common.basemapper.BaseEntity;
import lombok.Data;

import lombok.EqualsAndHashCode;

import javax.persistence.*;
import java.util.Date;
import java.io.Serializable;


/**
 * @author shuzhi
 * @date 2019-07-07 14:32:21
 */

@Table(name = "t_mq_message")
@Data
@EqualsAndHashCode(callSuper = true)
public class MqMessage extends BaseEntity implements Serializable{
private static final long serialVersionUID=1L;

    @Id
    private Integer id;
        
    /**
     * 设备类型
     */
    @Column(name = "type")
    private String type;
        
    /**
     * 厂商类型
     */
    @Column(name = "subtype")
    private String subtype;
        
    /**
     * 消息的exchange 
     */
    @Column(name = "exchange")
    private String exchange;
        
    /**
     * 消息的topic
     */
    @Column(name = "topic")
    private String topic;
        
    /**
     * 消息名称
     */
    @Column(name = "name")
    private String name;
    
}
