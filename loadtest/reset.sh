#!/bin/bash
docker exec marthub-mysql-1 mysql -uroot -proot marthub -e \
  "DELETE FROM orders; UPDATE flash_sale_items SET stock=500000;" 2>/dev/null
docker exec marthub-redis-1 redis-cli --no-raw EVAL "
  local n=0
  for _,p in ipairs({'fs:bought:*','fs:soldout:*','fs:processing:*','fs:rate:*','fs:gate:*'}) do
    local c='0'
    repeat
      local r=redis.call('SCAN',c,'MATCH',p,'COUNT',1000)
      c=r[1]
      if #r[2]>0 then redis.call('UNLINK',unpack(r[2])); n=n+#r[2] end
    until c=='0'
  end
  return n" 0 >/dev/null 2>&1
