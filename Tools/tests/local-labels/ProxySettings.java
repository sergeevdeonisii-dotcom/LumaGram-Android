package org.telegram.utils.proxy;
public class ProxySettings{
    public enum Type{SOCKS5}
    private final String address,password;
    public ProxySettings(String address,String password){this.address=address;this.password=password;}
    public boolean isValid(){return !address.isEmpty();}
    public Type getType(){return Type.SOCKS5;}
    public String getAddress(){return address;}
    public int getPort(){return 443;}
    public String getUser(){return "user";}
    public String getPassword(){return password;}
    public String getSecret(){return "";}
}
