package com.datasphere.government.gsp.datalineage.entity.xmls;

import com.thoughtworks.xstream.annotations.XStreamAlias;
import com.thoughtworks.xstream.annotations.XStreamAsAttribute;
import lombok.Data;

/**
 * Created by jeq 27.
 */
@Data
@XStreamAlias("source")
public class Source {

    @XStreamAsAttribute
    private String coordinate;
    @XStreamAsAttribute
    private String column;
    @XStreamAsAttribute
    private String id;
    @XStreamAsAttribute
    private String parentId;
    @XStreamAsAttribute
    private String parentName;

    public String getCoordinate() {
        return coordinate;
    }

    public void setCoordinate(String coordinate) {
        this.coordinate = coordinate;
    }

    public String getColumn() {
        return column;
    }

    public void setColumn(String column) {
        this.column = column;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getParentId() {
        return parentId;
    }

    public void setParentId(String parentId) {
        this.parentId = parentId;
    }

    public String getParentName() {
        return parentName;
    }

    public void setParentName(String parentName) {
        this.parentName = parentName;
    }
}
