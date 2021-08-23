package com.pushengage.pushengage.model.payload;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

import java.util.List;

public class PayloadPOJO {
    @SerializedName("ab")
    @Expose
    private String ab = null;
    @SerializedName("ad")
    @Expose
    private String ad;
    @SerializedName("b")
    @Expose
    private String b;
    @SerializedName("bp")
    @Expose
    private String bp;
    @SerializedName("ci")
    @Expose
    private String ci;
    @SerializedName("gi")
    @Expose
    private String gi;
    @SerializedName("gn")
    @Expose
    private String gn;
    @SerializedName("gk")
    @Expose
    private String gk;
    @SerializedName("i")
    @Expose
    private String i;
    @SerializedName("id")
    @Expose
    private Integer id;
    @SerializedName("im")
    @Expose
    private String im;
    @SerializedName("li")
    @Expose
    private String li;
    @SerializedName("p")
    @Expose
    private String p;
    @SerializedName("si")
    @Expose
    private String si;
    @SerializedName("tag")
    @Expose
    private String tag;
    @SerializedName("t")
    @Expose
    private String t;
    @SerializedName("u")
    @Expose
    private String u;
    @SerializedName("v")
    @Expose
    private String v;
    @SerializedName("rf")
    @Expose
    private String rf;
    @SerializedName("pb")
    @Expose
    private Object pb;
    @SerializedName("dl")
    @Expose
    private String dl;
    @SerializedName("ac")
    @Expose
    private String ac;
    @SerializedName("cu")
    @Expose
    private String cu;

    /**
     * No args constructor for use in serialization
     */
    public PayloadPOJO() {
    }

    /**
     * @param ab
     * @param b
     * @param gi
     * @param ad
     * @param gk
     * @param im
     * @param ci
     * @param gn
     * @param i
     * @param bp
     * @param p
     * @param pb
     * @param t
     * @param rf
     * @param u
     * @param si
     * @param v
     * @param id
     * @param tag
     * @param li
     * @param dl
     * @param ac
     */
    public PayloadPOJO(String ab, String ad, String b, String bp, String ci, String gi, String gn, String gk, String i, Integer id, String im, String li, String p,
                       String si, String tag, String t, String u, String v, String rf, Object pb, String dl, String ac, String cu) {
        super();
        this.ab = ab;
        this.ad = ad;
        this.b = b;
        this.bp = bp;
        this.ci = ci;
        this.gi = gi;
        this.gn = gn;
        this.gk = gk;
        this.i = i;
        this.id = id;
        this.im = im;
        this.li = li;
        this.p = p;
        this.si = si;
        this.tag = tag;
        this.t = t;
        this.u = u;
        this.v = v;
        this.rf = rf;
        this.pb = pb;
        this.dl = dl;
        this.ac = ac;
        this.cu = cu;
    }

    public String getAb() {
        return ab;
    }

    public void setAb(String ab) {
        this.ab = ab;
    }

    public String getAd() {
        return ad;
    }

    public void setAd(String ad) {
        this.ad = ad;
    }

    public String getB() {
        return b;
    }

    public void setB(String b) {
        this.b = b;
    }

    public String getBp() {
        return bp;
    }

    public void setBp(String bp) {
        this.bp = bp;
    }

    public String getCi() {
        return ci;
    }

    public void setCi(String ci) {
        this.ci = ci;
    }

    public String getGi() {
        return gi;
    }

    public void setGi(String gi) {
        this.gi = gi;
    }

    public String getGn() {
        return gn;
    }

    public void setGn(String gn) {
        this.gn = gn;
    }

    public String getGk() {
        return gk;
    }

    public void setGk(String gk) {
        this.gk = gk;
    }

    public String getI() {
        return i;
    }

    public void setI(String i) {
        this.i = i;
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getIm() {
        return im;
    }

    public void setIm(String im) {
        this.im = im;
    }

    public String getLi() {
        return li;
    }

    public void setLi(String li) {
        this.li = li;
    }

    public String getP() {
        return p;
    }

    public void setP(String p) {
        this.p = p;
    }

    public String getSi() {
        return si;
    }

    public void setSi(String si) {
        this.si = si;
    }

    public String getTag() {
        return tag;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }

    public String getT() {
        return t;
    }

    public void setT(String t) {
        this.t = t;
    }

    public String getU() {
        return u;
    }

    public void setU(String u) {
        this.u = u;
    }

    public String getV() {
        return v;
    }

    public void setV(String v) {
        this.v = v;
    }

    public String getRf() {
        return rf;
    }

    public void setRf(String rf) {
        this.rf = rf;
    }

    public Object getPb() {
        return pb;
    }

    public void setPb(Object pb) {
        this.pb = pb;
    }

    public String getDl() {
        return dl;
    }

    public void setDl(String dl) {
        this.dl = dl;
    }

    public String getAc() {
        return ac;
    }

    public void setAc(String ac) {
        this.ac = ac;
    }

    public String getCu() {
        return cu;
    }

    public void setCu(String cu) {
        this.cu = cu;
    }

    public class Ab {

        @SerializedName("a")
        @Expose
        private String a;
        @SerializedName("l")
        @Expose
        private String l;
        @SerializedName("i")
        @Expose
        private String i;
        @SerializedName("u")
        @Expose
        private String u;

        /**
         * No args constructor for use in serialization
         */
        public Ab() {
        }

        /**
         * @param a
         * @param i
         * @param l
         */
        public Ab(String a, String l, String i, String u) {
            super();
            this.a = a;
            this.l = l;
            this.i = i;
            this.u = u;
        }

        public String getA() {
            return a;
        }

        public void setA(String a) {
            this.a = a;
        }

        public String getL() {
            return l;
        }

        public void setL(String l) {
            this.l = l;
        }

        public String getI() {
            return i;
        }

        public void setI(String i) {
            this.i = i;
        }

        public String getU() {
            return u;
        }

        public void setU(String u) {
            this.u = u;
        }
    }
}