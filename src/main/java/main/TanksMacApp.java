package main;

public class TanksMacApp
{
    public static void main(String[] args)
    {
        String[] forwardedArgs = new String[args.length + 1];
        forwardedArgs[0] = "mac";
        System.arraycopy(args, 0, forwardedArgs, 1, args.length);
        Tanks.main(forwardedArgs);
    }
}
