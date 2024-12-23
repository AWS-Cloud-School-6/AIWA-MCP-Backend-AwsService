package AIWA.McpBackend.service.aws;


import AIWA.McpBackend.controller.api.dto.ec2.Ec2InstanceDTO;
import AIWA.McpBackend.controller.api.dto.eip.EipDto;
import AIWA.McpBackend.controller.api.dto.eni.NetworkInterfaceDto;
import AIWA.McpBackend.controller.api.dto.internetgateway.InternetGatewayDto;
import AIWA.McpBackend.controller.api.dto.natgateway.NatGatewayDto;
import AIWA.McpBackend.controller.api.dto.response.SingleResult;
import AIWA.McpBackend.controller.api.dto.routetable.RouteDTO;
import AIWA.McpBackend.controller.api.dto.routetable.RouteTableResponseDto;
import AIWA.McpBackend.controller.api.dto.securitygroup.SecurityGroupDTO;
import AIWA.McpBackend.controller.api.dto.subnet.SubnetResponseDto;
import AIWA.McpBackend.controller.api.dto.vpc.VpcTotalResponseDto;
import AIWA.McpBackend.provider.aws.api.dto.member.MemberCredentialDTO;

import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AwsResourceService {

    private final RestTemplate restTemplate;

    private Ec2Client ec2Client;

    public void initializeClient(String email,String companyName) {
        // 특정 멤버의 AWS 자격 증명 가져오기
        MemberCredentialDTO memberCredentialDto = getMemberCredentials(email,companyName);

        if (memberCredentialDto == null) {
            throw new IllegalArgumentException("회원 정보를 찾을 수 없습니다.");
        }

        // AWS 자격 증명 생성
        AwsBasicCredentials awsCredentials = AwsBasicCredentials.create(
                memberCredentialDto.getAiwaKeys().get(0).getAccessKey(),
                memberCredentialDto.getAiwaKeys().get(0).getSecretKey()
        );
        System.out.println(memberCredentialDto.getAiwaKeys().get(0).getAccessKey());
        System.out.println(memberCredentialDto.getAiwaKeys().get(0).getSecretKey());

        // EC2 클라이언트 생성
        this.ec2Client = Ec2Client.builder()
                .credentialsProvider(StaticCredentialsProvider.create(awsCredentials))
                .region(Region.of("ap-northeast-2")) // Member에서 리전 가져오기
                .build();
    }




    private MemberCredentialDTO getMemberCredentials(String email, String companyName) {
        // 새로운 경로에 맞는 URL 생성
        String url = String.format("http://member-svc/member/api/members/%s/%s", email, companyName);
        System.out.println("Request URL: " + url);

        try {
            // API 호출: SingleResult<MemberCredentialDTO> 형태의 응답 처리
            ResponseEntity<SingleResult<MemberCredentialDTO>> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    null,
                    new ParameterizedTypeReference<SingleResult<MemberCredentialDTO>>() {}
            );

            System.out.println(response.getBody().getData());

            // 응답 상태와 데이터 유효성 확인
            if (response.getStatusCode().is2xxSuccessful() &&
                    response.getBody() != null &&
                    response.getBody().isSuccess()) {
                // 성공적으로 데이터를 추출하여 반환
                return response.getBody().getData();
            } else {
                // 실패한 경우 null 반환
                System.err.println("Failed to retrieve data. Response: " + response);
                return null;
            }
        } catch (Exception e) {
            // 예외 발생 시 로그 출력 및 null 반환
            System.err.println("Exception while calling getMemberCredentials: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }


    // EC2 Instances 가져오기
    public List<Ec2InstanceDTO> fetchEc2Instances(String userId, String companyName) {
        // 클라이언트 초기화
        initializeClient(userId, companyName);

        // EC2 인스턴스 요청 생성
        DescribeInstancesRequest request = DescribeInstancesRequest.builder().build();
        DescribeInstancesResponse response = ec2Client.describeInstances(request);

        // EC2 인스턴스 정보를 저장할 리스트
        List<Ec2InstanceDTO> ec2Instances = new ArrayList<>();

        // EC2 인스턴스 정보 추출 및 DTO 생성
        response.reservations().forEach(reservation -> {
            reservation.instances().forEach(instance -> {
                String instanceState = instance.state().nameAsString(); // 인스턴스 상태
                Map<String, String> tagsMap = instance.tags() == null ? Collections.emptyMap() :
                        instance.tags().stream().collect(Collectors.toMap(Tag::key, Tag::value)); // 태그 맵
                String publicIpAddress = instance.publicIpAddress() == null ? "N/A" : instance.publicIpAddress(); // 퍼블릭 IP
                String privateIpAddress = instance.privateIpAddress() == null ? "N/A" : instance.privateIpAddress(); // 프라이빗 IP

                // DTO 객체 추가
                ec2Instances.add(new Ec2InstanceDTO(
                        instance.instanceId(),
                        instanceState,
                        tagsMap,
                        publicIpAddress,
                        privateIpAddress
                ));
            });
        });

        return ec2Instances;
    }


    // Subnets 가져오기
    public List<SubnetResponseDto> fetchSubnets(String userId,String companyName) {

        initializeClient(userId,companyName);
        DescribeSubnetsRequest request = DescribeSubnetsRequest.builder().build();
        DescribeSubnetsResponse response = ec2Client.describeSubnets(request);
        return response.subnets().stream()
                .map(subnet -> {
                    Map<String, String> tagsMap = subnet.tags() == null ? Collections.emptyMap() :
                            subnet.tags().stream().collect(Collectors.toMap(Tag::key, Tag::value));
                    return new SubnetResponseDto(subnet.subnetId(), subnet.cidrBlock(), subnet.vpcId(), tagsMap, subnet.availabilityZone());
                })
                .collect(Collectors.toList());
    }

    // Route Tables 가져오기
    public List<RouteTableResponseDto> fetchRouteTables(String userId,String companyName) {
        initializeClient(userId,companyName);

        // 서브넷 정보를 먼저 가져옵니다.
        // 모든 서브넷을 가져옵니다.
        List<SubnetResponseDto> allSubnets = fetchSubnets(userId,companyName);

        DescribeRouteTablesRequest request = DescribeRouteTablesRequest.builder().build();
        DescribeRouteTablesResponse response = ec2Client.describeRouteTables(request);

        return response.routeTables().stream()
                .map(routeTable -> {
                    Map<String, String> tagsMap = routeTable.tags() == null ? Collections.emptyMap() :
                            routeTable.tags().stream().collect(Collectors.toMap(Tag::key, Tag::value));

                    List<RouteDTO> routes = routeTable.routes().stream()
                            .map(route -> new RouteDTO(route.gatewayId(), route.destinationCidrBlock()))
                            .collect(Collectors.toList());
                    // 퍼블릭 여부 확인 (igw가 있는 경우 퍼블릭으로 간주)
                    boolean isPublicRouteTable = routeTable.routes().stream()
                            .anyMatch(route -> route.gatewayId() != null && route.gatewayId().startsWith("igw-"));
                    // 라우팅 테이블에 연결된 서브넷 필터링
                    List<SubnetResponseDto> associatedPublicSubnets = new ArrayList<>();
                    List<SubnetResponseDto> associatedPrivateSubnets = new ArrayList<>();
                    for (SubnetResponseDto subnet : allSubnets) {
                        if (subnet.getVpcId().equals(routeTable.vpcId())) {
                            if (isPublicRouteTable) {
                                associatedPublicSubnets.add(subnet);
                            } else {
                                associatedPrivateSubnets.add(subnet);
                            }
                        }
                    }
                    // 디버깅용 로그
                    System.out.println("Route Table ID: " + routeTable.routeTableId());
                    System.out.println("Public Subnets: " + associatedPublicSubnets);
                    System.out.println("Private Subnets: " + associatedPrivateSubnets);

                    return new RouteTableResponseDto(
                            routeTable.routeTableId(),
                            routeTable.vpcId(),
                            routes,
                            tagsMap,
                            associatedPublicSubnets,
                            associatedPrivateSubnets
                    );
                })
                .collect(Collectors.toList());
    }




    // VPCs 가져오기
    public List<VpcTotalResponseDto> fetchVpcs(String userId, String companyName) {
        initializeClient(userId, companyName);

        DescribeVpcsRequest request = DescribeVpcsRequest.builder().build();
        DescribeVpcsResponse response = ec2Client.describeVpcs(request);

        List<SubnetResponseDto> subnets = fetchSubnets(userId, companyName);
        List<RouteTableResponseDto> routeTables = fetchRouteTables(userId, companyName);

        return response.vpcs().stream()
                .map(vpc -> {
                    Map<String, String> tagsMap = vpc.tags() == null ? Collections.emptyMap() :
                            vpc.tags().stream().collect(Collectors.toMap(Tag::key, Tag::value));

                    List<SubnetResponseDto> associatedSubnets = subnets.stream()
                            .filter(subnet -> subnet.getVpcId().equals(vpc.vpcId()))
                            .collect(Collectors.toList());

                    // Null 체크를 추가하여 VpcId가 null인 경우 예외가 발생하지 않도록 처리
                    List<RouteTableResponseDto> associatedRouteTables = routeTables.stream()
                            .filter(routeTable -> routeTable.getVpcId() != null && routeTable.getVpcId().equals(vpc.vpcId()))
                            .collect(Collectors.toList());

                    return new VpcTotalResponseDto(vpc.vpcId(), vpc.cidrBlock(), tagsMap, associatedSubnets, associatedRouteTables);
                })
                .collect(Collectors.toList());
    }


    // Security Groups 가져오기
    public List<SecurityGroupDTO> fetchSecurityGroups(String userId, String companyName) {
        initializeClient(userId, companyName);

        // EC2 클라이언트로 보낼 요청 생성
        DescribeSecurityGroupsRequest request = DescribeSecurityGroupsRequest.builder().build();

        // EC2 클라이언트로부터 Security Group 정보 가져오기
        DescribeSecurityGroupsResponse response = ec2Client.describeSecurityGroups(request);

        return response.securityGroups().stream()
                .map(group -> {
                    // Security Group의 태그를 Map으로 변환
                    Map<String, String> tagsMap = group.tags() == null ? Collections.emptyMap() :
                            group.tags().stream().collect(Collectors.toMap(Tag::key, Tag::value));

                    // SecurityGroupDTO 객체를 반환 (vpcId도 포함)
                    return new SecurityGroupDTO(group.groupId(), group.groupName(), tagsMap, group.vpcId());
                })
                .collect(Collectors.toList());
    }


    public List<InternetGatewayDto> fetchInternetGateways(String userId,String companyName) {

        initializeClient(userId,companyName);
        DescribeInternetGatewaysRequest request = DescribeInternetGatewaysRequest.builder().build();
        DescribeInternetGatewaysResponse response = ec2Client.describeInternetGateways(request);

        return response.internetGateways().stream()
                .map(internetGateway -> {
                    // 태그를 맵으로 변환
                    Map<String, String> tagsMap = internetGateway.tags() == null ? Collections.emptyMap() :
                            internetGateway.tags().stream().collect(Collectors.toMap(Tag::key, Tag::value));

                    // Attachments의 정보를 적절하게 변환
                    List<InternetGatewayDto.Attachment> attachments = internetGateway.attachments().stream()
                            .map(attachment -> new InternetGatewayDto.Attachment(attachment.vpcId(), attachment.stateAsString())) // 상태를 문자열로 변환
                            .collect(Collectors.toList());

                    return new InternetGatewayDto(internetGateway.internetGatewayId(), tagsMap, attachments);
                })
                .collect(Collectors.toList());
    }

    // NAT Gateways 가져오기
    public List<NatGatewayDto> fetchNatGateways(String userId,String companyName) {
        initializeClient(userId,companyName);
        DescribeNatGatewaysRequest request = DescribeNatGatewaysRequest.builder().build();
        DescribeNatGatewaysResponse response = ec2Client.describeNatGateways(request);

        // ENI 정보를 가져옵니다.
        List<NetworkInterfaceDto> networkInterfaces = fetchNetworkInterfaces(userId,companyName);
        Map<String, List<NetworkInterfaceDto>> natGatewayEniMap = new HashMap<>();

        // NAT Gateway와 연결된 ENI 정보를 매핑합니다.
        for (NetworkInterfaceDto eni : networkInterfaces) {
            for (var natGateway : response.natGateways()) {
                for (var address : natGateway.natGatewayAddresses()) {
                    if (address.networkInterfaceId() != null && address.networkInterfaceId().equals(eni.getNetworkInterfaceId())) {
                        natGatewayEniMap
                                .computeIfAbsent(natGateway.natGatewayId(), k -> new ArrayList<>())
                                .add(eni);
                    }
                }
            }
        }

        return response.natGateways().stream()
                .map(natGateway -> {
                    Map<String, String> tagsMap = natGateway.tags() == null ? Collections.emptyMap() :
                            natGateway.tags().stream().collect(Collectors.toMap(Tag::key, Tag::value));

                    // ENI ID 리스트 추출
                    List<String> networkInterfaceIds = natGateway.natGatewayAddresses().stream()
                            .map(address -> address.networkInterfaceId())
                            .collect(Collectors.toList());

                    // ENI DTO 리스트 가져오기
                    List<NetworkInterfaceDto> eniList = natGatewayEniMap.getOrDefault(natGateway.natGatewayId(), Collections.emptyList());

                    // NatGatewayDto 생성
                    return new NatGatewayDto(
                            natGateway.natGatewayId(),
                            natGateway.stateAsString(),
                            tagsMap,
                            natGateway.vpcId(),
                            eniList // 연결된 ENI DTO 리스트
                    );
                })
                .collect(Collectors.toList());
    }


    public List<EipDto> fetchElasticIps(String userId,String companyName) {
        initializeClient(userId,companyName);
        DescribeAddressesRequest request = DescribeAddressesRequest.builder().build();
        DescribeAddressesResponse response = ec2Client.describeAddresses(request);

        return response.addresses().stream()
                .map(address -> {
                    Map<String, String> tagsMap = address.tags() == null ? Collections.emptyMap() :
                            address.tags().stream().collect(Collectors.toMap(Tag::key, Tag::value));

                    return new EipDto(address.allocationId(), address.publicIp(), address.domain().name(), tagsMap);
                })
                .collect(Collectors.toList());
    }



    public List<NetworkInterfaceDto> fetchNetworkInterfaces(String userId,String companyName) {
        initializeClient(userId,companyName);
        DescribeNetworkInterfacesRequest request = DescribeNetworkInterfacesRequest.builder().build();
        DescribeNetworkInterfacesResponse response = ec2Client.describeNetworkInterfaces(request);

        return response.networkInterfaces().stream()
                .map(networkInterface -> {
                    // 태그를 맵으로 변환
                    Map<String, String> tagsMap = (networkInterface.tagSet() == null || networkInterface.tagSet().isEmpty())
                            ? Collections.emptyMap()
                            : networkInterface.tagSet().stream().collect(Collectors.toMap(Tag::key, Tag::value));

                    List<String> privateIpAddresses = networkInterface.privateIpAddresses().stream()
                            .map(ipAddress -> ipAddress.privateIpAddress())
                            .collect(Collectors.toList());

                    // Public IP 리스트 초기화
                    List<String> publicIpAddresses = (networkInterface.association() != null && networkInterface.association().publicIp() != null)
                            ? List.of(networkInterface.association().publicIp())
                            : Collections.emptyList(); // Public IP가 없는 경우 빈 리스트

                    return new NetworkInterfaceDto(
                            networkInterface.networkInterfaceId(),
                            networkInterface.subnetId(),
                            networkInterface.vpcId(),
                            networkInterface.statusAsString(),
                            networkInterface.description(),
                            tagsMap,
                            privateIpAddresses,
                            publicIpAddresses // Public IP 추가
                    );
                })
                .collect(Collectors.toList());
    }


}
