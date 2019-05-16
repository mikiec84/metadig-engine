package edu.ucsb.nceas.mdqengine.model;

public class Node {

    private String nodeId;
    private String lastHarvestDatetime;
    private String solrLocation;

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public void setLastHarvestDatetime(String lastHarvestDatetime) {
        this.lastHarvestDatetime = lastHarvestDatetime;
    }

    public String getLastHarvestDatetime() {
        return lastHarvestDatetime;
    }

    public void setSolrLocation(String location) {
        this.solrLocation = location;
    }

    public String getSolrLocation() {
        return solrLocation;
    }


}

