package net.fseconomy.dto;

public class AircraftConfig2
{
    public String MakeModel;
    public int AddtlCrew;
    public int Seats;
    public int Paxs;
    public int Cruise;
    public int Ext1;
    public int LTip;
    public int LAux;
    public int LMain;
    public int Center1;
    public int Center2;
    public int Center3;
    public int RMain;
    public int RAux;
    public int RTip;
    public int Ext2;
    public int Gph;
    public int Engines;
    public String FuelType;
    public int Mtow;
    public int EmptyWeight;
    public int BasePrice;
    public int TotalFuel;
    public int PayloadNoFuel;
    public double EstEnduranceNM;
    public double EstEnduranceHrs;
    public double EstCostPerHr;
    public double EstCostPerNM;

    public void updateCalculatedFields(double fuel100ll, double fuelJetA)
    {
        Paxs = Seats - (1 + (AddtlCrew > 0 ? 1 : 0));
        PayloadNoFuel = Mtow - (EmptyWeight + (AddtlCrew == 0 ? 77 : 77*(1+AddtlCrew)));
        EstEnduranceNM = Cruise * ((double)TotalFuel / Gph);
        EstEnduranceHrs = (double)TotalFuel / Gph;
        EstCostPerHr = Gph * (FuelType.equals("100LL") ? fuel100ll : fuelJetA);
        EstCostPerNM = EstCostPerHr / Cruise;
    }

    public AircraftConfig2(String makemodel, int crew, int fueltype, int seats, int cruisespeed, int fcapExt1, int fcapLeftTip, int fcapLeftAux, int fcapLeftMain, int fcapCenter, int fcapCenter2, int fcapCenter3, int fcapRightMain, int fcapRightAux, int fcapRightTip, int fcapExt2, int gph, int maxWeight, int emptyWeight, int price, int engines, int fcaptotal)
    {
        MakeModel = makemodel;
        Seats = seats;
        AddtlCrew = crew;
        FuelType = fueltype == 1 ? "100LL" : "JetA";
        Cruise = cruisespeed;
        Ext1 = fcapExt1;
        LTip = fcapLeftTip;
        LAux = fcapLeftAux;
        LMain = fcapLeftMain;
        Center1 = fcapCenter;
        Center2 = fcapCenter2;
        Center3 = fcapCenter3;
        RMain = fcapRightMain;
        RAux = fcapRightAux;
        RTip = fcapRightTip;
        Ext2 = fcapExt2;
        Gph = gph;
        Mtow = maxWeight;
        EmptyWeight = emptyWeight;
        BasePrice = price;
        Engines = engines;
        TotalFuel = fcaptotal;
    }

}
