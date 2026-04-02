local roman = function()
    local map = {I = 1,V = 5,X = 10,L = 50,C = 100, D = 500, M = 1000}
    local numbers = { 1, 5, 10, 50, 100, 500, 1000 }
    local chars = { "I", "V", "X", "L", "C", "D", "M" }

local RomanNumerals = { }

function RomanNumerals.ToRomanNumerals(value)
        if not value or value > math.huge * .5 then return nil end
    value = math.floor(value)
        if value <= 0 then return value end
    local ret = ""
        for i = #numbers, 1, -1 do
        local num = numbers[i]
        while value - num >= 0 and value > 0 do ret = ret .. chars[i] value = value - num end
    for j = 1, i - 1 do
        local n2 = numbers[j]
        if value - (num - n2) >= 0 and value < num and value > 0 and num - n2 ~= n2 then ret = ret .. chars[j] .. chars[i] value = value - (num - n2) break end    
    end end return ret
end

function RomanNumerals.ToNumber(digit)
    digit = digit:upper()
    local ret,i = 0,1
    while i <= digit:len() do
        local c = digit:sub(i, i)
        if c ~= " " then 
            local m = map[c]
            local next = digit:sub(i + 1, i + 1)
            local nextm = map[next]
            
            if next and nextm then
                if nextm > m then ret = ret + (nextm - m) i = i + 1
                else ret = ret + m end
            else ret = ret + m end
        end
        i = i + 1
    end
    return ret
end
    return {toNumber = RomanNumerals.ToNumber, toRoman = RomanNumerals.ToRomanNumerals}
end

return roman()